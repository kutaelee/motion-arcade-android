package com.motionarcade.vision.capability.runtime;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The only authority issuer for native runtime ownership.
 *
 * All authority implementations are private static nestmates with private constructors. Public
 * operations perform the complete open/submit/close transition; no mint, claim, raw-runtime or
 * test-fixture operation exists.
 */
public final class RuntimeOwnerBoundary {
    private static final Object ISSUER_IDENTITY = new Object();
    private static final int MAX_TRACKED_SUBMISSIONS = 128;
    private static final int MAX_TRACKED_CALLBACK_OUTPUTS = 256;
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);
    private static final ConsumedCreateRequests CONSUMED_CREATE_REQUESTS =
            new ConsumedCreateRequests();

    private RuntimeOwnerBoundary() {}

    private enum CreateRequestClaimOutcome {
        ACQUIRED,
        DUPLICATE,
    }

    private static final class ConsumedCreateRequests {
        private final ConcurrentHashMap<RuntimeCreateRequest, Boolean> requests =
                new ConcurrentHashMap<>();

        private CreateRequestClaimOutcome claim(RuntimeCreateRequest request) {
            return requests.putIfAbsent(request, Boolean.TRUE) == null
                    ? CreateRequestClaimOutcome.ACQUIRED
                    : CreateRequestClaimOutcome.DUPLICATE;
        }
    }

    private static final class CreationBinding {
        private final PoseRuntime runtime;
        private final PoseRuntimeSubmissionPort submissionPort;
        private final PoseRuntimeInputSubmissionPort inputSubmissionPort;
        private final Object issuerIdentity;
        private final long generation;

        private CreationBinding(
                PoseRuntime runtime,
                PoseRuntimeSubmissionPort submissionPort,
                PoseRuntimeInputSubmissionPort inputSubmissionPort,
                Object issuerIdentity,
                long generation) {
            this.runtime = runtime;
            this.submissionPort = submissionPort;
            this.inputSubmissionPort = inputSubmissionPort;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    private static final class RuntimeCreationOwnerImpl implements BoundaryRuntimeCreationOwner {
        private final Object issuerIdentity;
        private final long generation;
        private boolean sealed;
        private CreationBinding binding;

        private RuntimeCreationOwnerImpl(Object issuerIdentity, long generation) {
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }

        @Override
        public synchronized boolean bind(
                PoseRuntime runtime,
                PoseRuntimeSubmissionPort submissionPort) {
            return bindExact(runtime, submissionPort, null);
        }

        @Override
        public synchronized boolean bindInputAware(
                PoseRuntime runtime,
                PoseRuntimeInputSubmissionPort submissionPort) {
            return bindExact(runtime, null, submissionPort);
        }

        private boolean bindExact(
                PoseRuntime runtime,
                PoseRuntimeSubmissionPort submissionPort,
                PoseRuntimeInputSubmissionPort inputSubmissionPort) {
            if (sealed || binding != null || generation <= 0L || runtime == null
                    || (submissionPort == null) == (inputSubmissionPort == null)) {
                return false;
            }
            binding = new CreationBinding(
                    runtime,
                    submissionPort,
                    inputSubmissionPort,
                    issuerIdentity,
                    generation);
            return true;
        }

        private synchronized CreationBinding sealAndTakeBinding() {
            sealed = true;
            CreationBinding captured = binding;
            binding = null;
            return captured;
        }
    }

    private enum OwnedResourceState {
        OWNED,
        CLOSING,
        CLOSED,
        POISONED,
    }

    private static final class RuntimeMachineBindingImpl implements BoundaryRuntimeMachineBinding {
        private final OwnedRuntimeResource resource;
        private final Object issuerIdentity;
        private final long generation;

        private RuntimeMachineBindingImpl(
                OwnedRuntimeResource resource,
                Object issuerIdentity,
                long generation) {
            this.resource = resource;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    /** One unforgeable identity spanning dependency entry, return evidence, and callback output. */
    private static final class OwnedRuntimeSubmission {
        private final SubmitRuntimeCommand command;
        private final Object input;
        private final Object issuerIdentity;
        private final long generation;
        private volatile RecordedRuntimeSubmitExecution execution;

        private OwnedRuntimeSubmission(
                SubmitRuntimeCommand command,
                Object input,
                Object issuerIdentity,
                long generation) {
            this.command = command;
            this.input = input;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    private static final class OwnedRuntimeCallbackOutput {
        private final OwnedRuntimeSubmission submission;
        private final RuntimeResultCallback callback;
        private final Object output;
        private final Object issuerIdentity;
        private final long generation;
        private final AtomicBoolean claimed = new AtomicBoolean(false);

        private OwnedRuntimeCallbackOutput(
                OwnedRuntimeSubmission submission,
                RuntimeResultCallback callback,
                Object output,
                Object issuerIdentity,
                long generation) {
            this.submission = submission;
            this.callback = callback;
            this.output = output;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    private static final class RecordedRuntimeCallbackOutputExecution
            implements BoundaryRuntimeCallbackOutputExecution {
        private final OwnedRuntimeCallbackOutput record;
        private final OpenedRuntimeOpenExecution opened;
        private final Object issuerIdentity;
        private final long generation;
        private final AtomicBoolean consumed = new AtomicBoolean(false);

        private RecordedRuntimeCallbackOutputExecution(
                OwnedRuntimeCallbackOutput record,
                OpenedRuntimeOpenExecution opened,
                Object issuerIdentity,
                long generation) {
            this.record = record;
            this.opened = opened;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    /** Adapter-facing callback port. The downstream never receives an unregistered output object. */
    private static final class RuntimeCallbackBridge implements RuntimeCallbackPort {
        private final RuntimeCallbackPort downstream;
        private final AtomicReference<OwnedRuntimeResource> resource = new AtomicReference<>();

        private RuntimeCallbackBridge(RuntimeCallbackPort downstream) {
            this.downstream = downstream;
        }

        private boolean bind(OwnedRuntimeResource exactResource) {
            return exactResource != null && resource.compareAndSet(null, exactResource);
        }

        @Override
        public void onResult(RuntimeResultCallback callback) {
            downstream.onResult(callback);
        }

        @Override
        public void onResultWithOutput(RuntimeResultCallback callback, Object callbackOutput) {
            OwnedRuntimeResource exactResource = resource.get();
            if (exactResource != null) {
                exactResource.registerCallbackOutput(callback, callbackOutput);
            }
            downstream.onResultWithOutput(callback, callbackOutput);
        }

        @Override
        public void onError(RuntimeErrorCallback callback) {
            downstream.onError(callback);
        }
    }

    private static final class OwnedRuntimeResource {
        private final PoseRuntime runtime;
        private final PoseRuntimeSubmissionPort submissionPort;
        private final PoseRuntimeInputSubmissionPort inputSubmissionPort;
        private final Object issuerIdentity;
        private final long generation;
        private final Object operationGate = new Object();
        private OwnedResourceState state = OwnedResourceState.OWNED;
        private SubmitRuntimeCommand activeSubmitCommand;
        private long highestReservationToken;
        private long highestTaskTimestampValue = -1L;
        private SubmitRuntimeCommand lastSubmitCommand;
        private RuntimeSubmitExecution lastSubmitExecution;
        private OwnedRuntimeSubmission activeSubmission;
        private OwnedRuntimeSubmission lastSubmission;
        private final HashMap<Long, OwnedRuntimeSubmission> submissionsByTaskTimestamp =
                new HashMap<>();
        private final IdentityHashMap<Object, OwnedRuntimeCallbackOutput> callbackOutputs =
                new IdentityHashMap<>();
        private RuntimeCreateCleanupOutcome closeOutcome;
        private RuntimeMachineBindingImpl machineBinding;
        private ProbeStateMachine machine;
        private volatile OpenedRuntimeOpenExecution openedExecution;

        private OwnedRuntimeResource(
                PoseRuntime runtime,
                PoseRuntimeSubmissionPort submissionPort,
                PoseRuntimeInputSubmissionPort inputSubmissionPort,
                Object issuerIdentity,
                long generation) {
            this.runtime = runtime;
            this.submissionPort = submissionPort;
            this.inputSubmissionPort = inputSubmissionPort;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }

        private boolean isOwned() {
            synchronized (operationGate) {
                return state == OwnedResourceState.OWNED;
            }
        }

        private RuntimeMachineBindingImpl claimMachineBinding() {
            synchronized (operationGate) {
                if (state != OwnedResourceState.OWNED || machineBinding != null || machine != null) {
                    return null;
                }
                machineBinding = new RuntimeMachineBindingImpl(this, issuerIdentity, generation);
                return machineBinding;
            }
        }

        private boolean bindMachine(
                RuntimeMachineBindingImpl binding,
                ProbeStateMachine candidate) {
            synchronized (operationGate) {
                if (state != OwnedResourceState.OWNED
                        || binding == null
                        || binding != machineBinding
                        || binding.resource != this
                        || binding.issuerIdentity != ISSUER_IDENTITY
                        || binding.generation != generation
                        || machine != null) {
                    return false;
                }
                machine = candidate;
                return true;
            }
        }

        private boolean ownsMachine(ProbeStateMachine candidate) {
            synchronized (operationGate) {
                return machine != null && machine == candidate;
            }
        }

        private boolean publishOpen(OpenedRuntimeOpenExecution opened) {
            synchronized (operationGate) {
                if (state != OwnedResourceState.OWNED || machine == null
                        || openedExecution != null || opened.resource != this
                        || opened.machine != machine) {
                    return false;
                }
                openedExecution = opened;
                return true;
            }
        }

        private boolean ownsBinding(RuntimeMachineBindingImpl candidate) {
            synchronized (operationGate) {
                return machineBinding == candidate;
            }
        }

        private long readIdentity() {
            if (!isOwned()) {
                throw new IllegalStateException("runtime resource is not owned");
            }
            return PoseRuntimeContractKt.boundaryRuntimeIdentityValue(runtime);
        }

        private RuntimeSubmitExecution submit(SubmitRuntimeCommand command, Object input) {
            final OwnedRuntimeSubmission submission;
            synchronized (operationGate) {
                if (state != OwnedResourceState.OWNED
                        || (input == null && submissionPort == null)
                        || (input != null && inputSubmissionPort == null)) {
                    return recordSubmitFailure(
                            command,
                            SubmissionFailure.RUNTIME_OWNERSHIP_INVALID);
                }

                long reservationToken =
                        PoseRuntimeContractKt.boundarySubmitReservationToken(command);
                long taskTimestampValue =
                        PoseRuntimeContractKt.boundarySubmitTaskTimestampValue(command);
                if (activeSubmitCommand != null) {
                    SubmissionFailure reason;
                    if (PoseRuntimeContractKt.boundarySubmitReservationToken(activeSubmitCommand)
                            == reservationToken) {
                        reason = activeSubmitCommand.equals(command)
                                ? SubmissionFailure.COMMAND_REPLAY_IN_FLIGHT
                                : SubmissionFailure.COMMAND_KEY_COLLISION;
                    } else {
                        reason = SubmissionFailure.SUBMISSION_IN_FLIGHT;
                    }
                    return recordSubmitFailure(command, reason);
                }
                if (highestReservationToken > 0L) {
                    if (reservationToken < highestReservationToken
                            || taskTimestampValue < highestTaskTimestampValue) {
                        return recordSubmitFailure(
                                command,
                                SubmissionFailure.COMMAND_OUT_OF_ORDER);
                    }
                    if (reservationToken == highestReservationToken
                            || taskTimestampValue == highestTaskTimestampValue) {
                        if (lastSubmitCommand != null
                                && lastSubmitCommand.equals(command)
                                && lastSubmission != null
                                && lastSubmission.input == input
                                && lastSubmitExecution != null) {
                            return lastSubmitExecution;
                        }
                        return recordSubmitFailure(
                                command,
                                SubmissionFailure.COMMAND_KEY_COLLISION);
                    }
                }

                highestReservationToken = reservationToken;
                highestTaskTimestampValue = taskTimestampValue;
                lastSubmitCommand = command;
                lastSubmitExecution = null;
                activeSubmitCommand = command;
                submission = new OwnedRuntimeSubmission(
                        command,
                        input,
                        ISSUER_IDENTITY,
                        generation);
                if (submissionsByTaskTimestamp.size() >= MAX_TRACKED_SUBMISSIONS
                        || submissionsByTaskTimestamp.put(taskTimestampValue, submission) != null) {
                    state = OwnedResourceState.POISONED;
                    closeOutcome = RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                    activeSubmitCommand = null;
                    return recordSubmitFailure(
                            command,
                            SubmissionFailure.COMMAND_KEY_COLLISION);
                }
                activeSubmission = submission;
            }

            final RuntimeSubmitResult result;
            try {
                PoseRuntimeSubmitEvidence evidence = input == null
                        ? submissionPort.submit(command.getSubmission())
                        : inputSubmissionPort.submit(command.getSubmission(), input);
                switch (evidence) {
                    case RETURNED:
                        result = new RuntimeSubmitResult.Returned(command.getKey());
                        break;
                    case FAILED:
                        result = new RuntimeSubmitResult.Failed(
                                command.getKey(),
                                SubmissionFailure.DEPENDENCY_FAILED);
                        break;
                    case DEADLINE_EXPIRED:
                        result = new RuntimeSubmitResult.DeadlineExpired(command.getKey());
                        break;
                    default:
                        throw new AssertionError("unhandled submit evidence");
                }
            } catch (Throwable delivered) {
                RuntimeSubmitExecution execution = recordSubmitFailure(
                        command,
                        SubmissionFailure.DEPENDENCY_THROW,
                        submission);
                completeSubmit(command, submission, execution);
                return execution;
            }
            RecordedRuntimeSubmitExecution execution = new RecordedRuntimeSubmitExecution(
                    command,
                    result,
                    this,
                    machine,
                    ISSUER_IDENTITY,
                    generation,
                    submission);
            submission.execution = execution;
            completeSubmit(command, submission, execution);
            return execution;
        }

        private RuntimeSubmitExecution recordSubmitFailure(
                SubmitRuntimeCommand command,
                SubmissionFailure reason) {
            return new RecordedRuntimeSubmitExecution(
                    command,
                    new RuntimeSubmitResult.Failed(command.getKey(), reason),
                    this,
                    machine,
                    ISSUER_IDENTITY,
                    generation);
        }

        private RuntimeSubmitExecution recordSubmitFailure(
                SubmitRuntimeCommand command,
                SubmissionFailure reason,
                OwnedRuntimeSubmission submission) {
            RecordedRuntimeSubmitExecution execution = new RecordedRuntimeSubmitExecution(
                    command,
                    new RuntimeSubmitResult.Failed(command.getKey(), reason),
                    this,
                    machine,
                    ISSUER_IDENTITY,
                    generation,
                    submission);
            submission.execution = execution;
            return execution;
        }

        private void completeSubmit(
                SubmitRuntimeCommand command,
                OwnedRuntimeSubmission submission,
                RuntimeSubmitExecution execution) {
            synchronized (operationGate) {
                if (activeSubmitCommand != command || activeSubmission != submission) {
                    state = OwnedResourceState.POISONED;
                    closeOutcome = RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                    throw new IllegalStateException("runtime submit reservation was lost");
                }
                lastSubmitExecution = execution;
                lastSubmission = submission;
                activeSubmitCommand = null;
                activeSubmission = null;
                operationGate.notifyAll();
            }
        }

        private void registerCallbackOutput(
                RuntimeResultCallback callback,
                Object output) {
            if (callback == null || output == null) {
                return;
            }
            synchronized (operationGate) {
                long callbackIdentity =
                        PoseRuntimeContractKt.boundaryResultCallbackIdentityValue(callback);
                if (callbackIdentity != PoseRuntimeContractKt.boundaryRuntimeIdentityValue(runtime)) {
                    return;
                }
                OwnedRuntimeSubmission submission =
                        submissionsByTaskTimestamp.get(callback.getTaskTimestampMs());
                if (submission == null
                        || submission.issuerIdentity != ISSUER_IDENTITY
                        || submission.generation != generation
                        || callbackOutputs.size() >= MAX_TRACKED_CALLBACK_OUTPUTS
                        || callbackOutputs.containsKey(output)) {
                    state = OwnedResourceState.POISONED;
                    closeOutcome = RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                    return;
                }
                callbackOutputs.put(
                        output,
                        new OwnedRuntimeCallbackOutput(
                                submission,
                                callback,
                                output,
                                ISSUER_IDENTITY,
                                generation));
            }
        }

        private OwnedRuntimeCallbackOutput claimCallbackOutput(
                RuntimeResultCallback callback,
                Object output) {
            synchronized (operationGate) {
                OwnedRuntimeCallbackOutput record = callbackOutputs.get(output);
                if (record == null
                        || record.callback != callback
                        || record.output != output
                        || record.issuerIdentity != ISSUER_IDENTITY
                        || record.generation != generation
                        || record.submission.execution == null
                        || !record.claimed.compareAndSet(false, true)) {
                    return null;
                }
                return record;
            }
        }

        private RuntimeCreateCleanupOutcome closeOnce() {
            synchronized (operationGate) {
                if (activeSubmitCommand != null) {
                    return RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                }
                if (state == OwnedResourceState.CLOSED || state == OwnedResourceState.POISONED) {
                    return closeOutcome == null
                            ? RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN
                            : closeOutcome;
                }
                if (state != OwnedResourceState.OWNED) {
                    return RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                }
                state = OwnedResourceState.CLOSING;
            }
            RuntimeCreateCleanupOutcome outcome = performNativeClose();
            completeClose(outcome);
            return outcome;
        }

        private RuntimeCloseResult close(CloseRuntimeCommand command) {
            synchronized (operationGate) {
                if (activeSubmitCommand != null) {
                    return PoseRuntimeContractKt.boundaryCloseDeferred(
                            command,
                            RuntimeCloseDeferredReason.SUBMISSION_IN_FLIGHT);
                }
                if (state == OwnedResourceState.CLOSING) {
                    return PoseRuntimeContractKt.boundaryCloseDeferred(
                            command,
                            RuntimeCloseDeferredReason.CLOSE_IN_FLIGHT);
                }
                if (state == OwnedResourceState.CLOSED || state == OwnedResourceState.POISONED) {
                    return closeResult(command, closeOutcome);
                }
                state = OwnedResourceState.CLOSING;
            }
            RuntimeCreateCleanupOutcome outcome = performNativeClose();
            completeClose(outcome);
            return closeResult(command, outcome);
        }

        private RuntimeCreateCleanupOutcome performNativeClose() {
            final RuntimeCloseEvidence evidence;
            try {
                evidence = runtime.close();
            } catch (Throwable delivered) {
                return RuntimeCreateCleanupOutcome.CALL_THREW;
            }
            switch (evidence) {
                case CLEAN:
                    return RuntimeCreateCleanupOutcome.CLEAN;
                case THREW:
                    return RuntimeCreateCleanupOutcome.REPORTED_FAILURE;
                case TIMED_OUT:
                    return RuntimeCreateCleanupOutcome.TIMED_OUT;
                case RESOURCE_UNCERTAIN:
                    return RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
                default:
                    throw new AssertionError("unhandled close evidence");
            }
        }

        private void completeClose(RuntimeCreateCleanupOutcome outcome) {
            synchronized (operationGate) {
                closeOutcome = outcome;
                state = outcome == RuntimeCreateCleanupOutcome.CLEAN
                        ? OwnedResourceState.CLOSED
                        : OwnedResourceState.POISONED;
                operationGate.notifyAll();
            }
        }

        private RuntimeCloseResult closeResult(
                CloseRuntimeCommand command,
                RuntimeCreateCleanupOutcome outcome) {
            if (outcome == null) {
                return closeFailure(command, RuntimeCloseFailure.RESOURCE_UNCERTAIN);
            }
            switch (outcome) {
                case CLEAN:
                    return PoseRuntimeContractKt.boundaryCloseClean(command);
                case REPORTED_FAILURE:
                case CALL_THREW:
                    return closeFailure(command, RuntimeCloseFailure.CLOSE_THREW);
                case TIMED_OUT:
                    return closeFailure(command, RuntimeCloseFailure.CLOSE_TIMED_OUT);
                case RESOURCE_UNCERTAIN:
                    return closeFailure(command, RuntimeCloseFailure.RESOURCE_UNCERTAIN);
                default:
                    throw new AssertionError("unhandled cleanup outcome");
            }
        }
    }

    private static final class OpenedRuntimeOpenExecution implements BoundaryRuntimeOpenExecution {
        private final RuntimeOpenResult.Opened result;
        private final RuntimeCreateRequest request;
        private final OwnedRuntimeResource resource;
        private final ProbeStateMachine machine;
        private final Object issuerIdentity;
        private final long generation;
        private final AtomicReference<RecoveryClosureBeginToken> recoveryClosureClaim =
                new AtomicReference<>();

        private OpenedRuntimeOpenExecution(
                RuntimeOpenResult.Opened result,
                RuntimeCreateRequest request,
                OwnedRuntimeResource resource,
                ProbeStateMachine machine,
                Object issuerIdentity,
                long generation) {
            this.result = result;
            this.request = request;
            this.resource = resource;
            this.machine = machine;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }

        @Override
        public RuntimeOpenResult.Opened getResult() {
            return result;
        }

        @Override
        public boolean getOwnsRuntime() {
            return resource.isOwned();
        }
    }

    private static final class FailedRuntimeOpenExecution implements BoundaryRuntimeOpenExecution {
        private final RuntimeOpenResult.Failed result;

        private FailedRuntimeOpenExecution(RuntimeOpenResult.Failed result) {
            this.result = result;
        }

        @Override
        public RuntimeOpenResult.Failed getResult() {
            return result;
        }

        @Override
        public boolean getOwnsRuntime() {
            return false;
        }
    }

    private static final class RecordedRuntimeSubmitExecution implements BoundaryRuntimeSubmitExecution {
        private final SubmitRuntimeCommand command;
        private final RuntimeSubmitResult result;
        private final OwnedRuntimeResource resource;
        private final ProbeStateMachine machine;
        private final Object issuerIdentity;
        private final long generation;
        private final OwnedRuntimeSubmission submissionIdentity;

        private RecordedRuntimeSubmitExecution(
                SubmitRuntimeCommand command,
                RuntimeSubmitResult result,
                OwnedRuntimeResource resource,
                ProbeStateMachine machine,
                Object issuerIdentity,
                long generation) {
            this(command, result, resource, machine, issuerIdentity, generation, null);
        }

        private RecordedRuntimeSubmitExecution(
                SubmitRuntimeCommand command,
                RuntimeSubmitResult result,
                OwnedRuntimeResource resource,
                ProbeStateMachine machine,
                Object issuerIdentity,
                long generation,
                OwnedRuntimeSubmission submissionIdentity) {
            if (!command.getKey().equals(result.getKey()) || generation <= 0L) {
                throw new IllegalArgumentException("invalid submit execution binding");
            }
            this.command = command;
            this.result = result;
            this.resource = resource;
            this.machine = machine;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
            this.submissionIdentity = submissionIdentity;
        }

        @Override
        public SubmitRuntimeCommand getCommand() {
            return command;
        }

        @Override
        public RuntimeSubmitResult getResult() {
            return result;
        }
    }

    private static final class RecordedRuntimeCloseExecution implements BoundaryRuntimeCloseExecution {
        private final CloseRuntimeCommand command;
        private final RuntimeCloseResult result;
        private final OwnedRuntimeResource resource;
        private final ProbeStateMachine machine;
        private final ProbeCloseClaim claim;
        private final OpenedRuntimeOpenExecution opened;
        private final Object issuerIdentity;
        private final long generation;

        private RecordedRuntimeCloseExecution(
                CloseRuntimeCommand command,
                RuntimeCloseResult result,
                OwnedRuntimeResource resource,
                ProbeStateMachine machine,
                ProbeCloseClaim claim,
                OpenedRuntimeOpenExecution opened,
                Object issuerIdentity,
                long generation) {
            this.command = command;
            this.result = result;
            this.resource = resource;
            this.machine = machine;
            this.claim = claim;
            this.opened = opened;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }

        @Override
        public CloseRuntimeCommand getCommand() {
            return command;
        }

        @Override
        public RuntimeCloseResult getResult() {
            return result;
        }
    }

    private static final class OrphanCloseAuthorityImpl {
        private final OwnedRuntimeResource resource;
        private final ProbeRoutePlanner planner;
        private final OrphanRuntimeCleanup cleanup;
        private final Object issuerIdentity;
        private final long generation;

        private OrphanCloseAuthorityImpl(
                OwnedRuntimeResource resource,
                ProbeRoutePlanner planner,
                OrphanRuntimeCleanup cleanup,
                Object issuerIdentity,
                long generation) {
            this.resource = resource;
            this.planner = planner;
            this.cleanup = cleanup;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    private static final class RecordedOrphanRuntimeCloseExecution
            implements BoundaryOrphanRuntimeCloseExecution {
        private final CloseRuntimeCommand command;
        private final RuntimeCloseResult result;
        private final OwnedRuntimeResource resource;
        private final OpenedRuntimeOpenExecution opened;
        private final OrphanCloseAuthorityImpl authority;
        private final Object issuerIdentity;
        private final long generation;

        private RecordedOrphanRuntimeCloseExecution(
                CloseRuntimeCommand command,
                RuntimeCloseResult result,
                OwnedRuntimeResource resource,
                OpenedRuntimeOpenExecution opened,
                OrphanCloseAuthorityImpl authority,
                Object issuerIdentity,
                long generation) {
            this.command = command;
            this.result = result;
            this.resource = resource;
            this.opened = opened;
            this.authority = authority;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }

        @Override
        public CloseRuntimeCommand getCommand() {
            return command;
        }

        @Override
        public RuntimeCloseResult getResult() {
            return result;
        }
    }

    public static RuntimeOpenExecution open(
            OpenRuntimeCommand command,
            NativeCreateAuthorizationRequest authorizationRequest,
            NativeCreateAuthorizer authorizer,
            RuntimeCallbackPort callbacks,
            FreshPoseRuntimeFactory runtimeFactory,
            ProbeClock clock) {
        return openInternal(
                command,
                authorizationRequest,
                authorizer,
                callbacks,
                runtimeFactory,
                clock,
                null);
    }

    /** Recovery entry whose safety callback executes immediately before factory/native create. */
    public static RuntimeOpenExecution openWithPreCreateGate(
            OpenRuntimeCommand command,
            NativeCreateAuthorizationRequest authorizationRequest,
            NativeCreateAuthorizer authorizer,
            RuntimeCallbackPort callbacks,
            FreshPoseRuntimeFactory runtimeFactory,
            ProbeClock clock,
            RuntimeNativeCreateGate preCreateGate) {
        if (preCreateGate == null) {
            return failedOpen(
                    command.getRequest().getRoute().getKey(),
                    RuntimeOpenFailure.PRE_CREATE_SAFETY_REJECTED.INSTANCE);
        }
        return openInternal(
                command,
                authorizationRequest,
                authorizer,
                callbacks,
                runtimeFactory,
                clock,
                preCreateGate);
    }

    private static RuntimeOpenExecution openInternal(
            OpenRuntimeCommand command,
            NativeCreateAuthorizationRequest authorizationRequest,
            NativeCreateAuthorizer authorizer,
            RuntimeCallbackPort callbacks,
            FreshPoseRuntimeFactory runtimeFactory,
            ProbeClock clock,
            RuntimeNativeCreateGate preCreateGate) {
        RuntimeRouteKey routeKey = command.getRequest().getRoute().getKey();
        if (!authorizationRequest.getRequest().equals(command.getRequest())) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.AuthorizationDenied(
                            NativeCreateDenialReason.AUTHORIZATION_BINDING_MISMATCH));
        }

        final NativeCreateAuthorization authorization;
        try {
            authorization = authorizer.authorize(authorizationRequest);
        } catch (Throwable delivered) {
            return failedOpen(routeKey, RuntimeOpenFailure.AUTHORIZER_THREW.INSTANCE);
        }
        NativeCreateConsumeResult consumed = NativeCreateAuthorizerKt
                .consumeNativeCreateAuthorization(authorization, authorizationRequest);
        if (consumed instanceof NativeCreateConsumeResult.Denied) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.AuthorizationDenied(
                            ((NativeCreateConsumeResult.Denied) consumed).getReason()));
        }

        CreateRequestClaimOutcome claimOutcome =
                CONSUMED_CREATE_REQUESTS.claim(command.getRequest());
        if (claimOutcome == CreateRequestClaimOutcome.DUPLICATE) {
            return failedOpen(
                    routeKey,
                    RuntimeOpenFailure.DUPLICATE_RUNTIME_CREATE_REQUEST.INSTANCE);
        }
        Long generation = allocateGeneration();
        if (generation == null) {
            return failedOpen(
                    routeKey,
                    RuntimeOpenFailure.OWNERSHIP_GENERATION_EXHAUSTED.INSTANCE);
        }
        RuntimeCreationOwnerImpl creationOwner =
                new RuntimeCreationOwnerImpl(ISSUER_IDENTITY, generation);
        RuntimeCallbackBridge callbackBridge = new RuntimeCallbackBridge(callbacks);

        if (preCreateGate != null) {
            final boolean currentSafe;
            try {
                currentSafe = preCreateGate.isCurrentSafe();
            } catch (Throwable delivered) {
                return failedOpen(
                        routeKey,
                        RuntimeOpenFailure.PRE_CREATE_SAFETY_REJECTED.INSTANCE);
            }
            if (!currentSafe) {
                return failedOpen(
                        routeKey,
                        RuntimeOpenFailure.PRE_CREATE_SAFETY_REJECTED.INSTANCE);
            }
        }

        final PoseRuntime runtime;
        try {
            runtime = runtimeFactory.create(command.getRequest(), callbackBridge, creationOwner);
        } catch (Throwable delivered) {
            CreationBinding binding = creationOwner.sealAndTakeBinding();
            if (binding == null) {
                return failedOpen(routeKey, RuntimeOpenFailure.CREATE_THREW.INSTANCE);
            }
            OwnedRuntimeResource resource = ownedResource(binding);
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreateThrewAfterBinding(resource.closeOnce()));
        }

        CreationBinding binding = creationOwner.sealAndTakeBinding();
        if (binding == null
                || binding.runtime != runtime
                || binding.issuerIdentity != ISSUER_IDENTITY
                || binding.generation != generation) {
            RuntimeCreateCleanupOutcome returnedCleanup = new OwnedRuntimeResource(
                    runtime,
                    null,
                    null,
                    ISSUER_IDENTITY,
                    generation).closeOnce();
            RuntimeCreateCleanupOutcome boundCleanup =
                    binding != null && binding.runtime != runtime
                            ? ownedResource(binding).closeOnce()
                            : RuntimeCreateCleanupOutcome.CLEAN;
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(
                            combineCleanup(returnedCleanup, boundCleanup)));
        }

        OwnedRuntimeResource resource = ownedResource(binding);
        if (!callbackBridge.bind(resource)) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(resource.closeOnce()));
        }
        final long runtimeIdentity;
        try {
            runtimeIdentity = resource.readIdentity();
        } catch (Throwable delivered) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.IdentityReadThrew(resource.closeOnce()));
        }
        RuntimeOpenResult.Opened result =
                PoseRuntimeContractKt.boundaryOpenedResult(routeKey, runtimeIdentity);
        RuntimeMachineBindingImpl machineBinding = resource.claimMachineBinding();
        if (machineBinding == null) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(resource.closeOnce()));
        }
        final ProbeStateMachine machine;
        try {
            machine = PoseRuntimeContractKt.boundaryProbeStateMachine(
                    machineBinding,
                    routeKey,
                    runtimeIdentity,
                    command.getRequest().getRoute().getKind().getRole(),
                    clock);
        } catch (Throwable delivered) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(resource.closeOnce()));
        }
        if (!resource.bindMachine(machineBinding, machine)) {
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(resource.closeOnce()));
        }
        OpenedRuntimeOpenExecution opened = new OpenedRuntimeOpenExecution(
                result,
                command.getRequest(),
                resource,
                machine,
                ISSUER_IDENTITY,
                generation);
        if (!resource.publishOpen(opened)) {
            resource.closeOnce();
            return failedOpen(
                    routeKey,
                    new RuntimeOpenFailure.CreationBindingInvalid(
                            RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN));
        }
        return opened;
    }

    /** Exact-class verifier used before a planner admits an opaque open execution. */
    public static boolean isGenuineOpenExecution(RuntimeOpenExecution execution) {
        if (execution == null) {
            return false;
        }
        if (execution.getClass() == FailedRuntimeOpenExecution.class) {
            return true;
        }
        return genuineOpened(execution) != null;
    }

    public static boolean isGenuineMachineBinding(RuntimeMachineBinding binding) {
        if (binding == null || binding.getClass() != RuntimeMachineBindingImpl.class) {
            return false;
        }
        RuntimeMachineBindingImpl issued = (RuntimeMachineBindingImpl) binding;
        return issued.issuerIdentity == ISSUER_IDENTITY
                && issued.generation > 0L
                && issued.resource.issuerIdentity == ISSUER_IDENTITY
                && issued.resource.generation == issued.generation
                && issued.resource.ownsBinding(issued);
    }

    public static ProbeStateMachine stateMachine(RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        return opened == null ? null : opened.machine;
    }

    private enum RecoveryClosureClaimState {
        CLAIMED,
        COMMITTED,
        ROLLED_BACK,
    }

    /**
     * Opaque one-open/one-session recovery claim. A token is never returned by a successful
     * recovery-session begin, and rollback is valid only before that begin commits the exact
     * session identity.
     */
    public static final class RecoveryClosureBeginToken {
        private final OpenedRuntimeOpenExecution opened;
        private final Object issuerIdentity;
        private final long generation;
        private final AtomicReference<RecoveryClosureClaimState> state =
                new AtomicReference<>(RecoveryClosureClaimState.CLAIMED);
        private volatile Object committedSessionIdentity;

        private RecoveryClosureBeginToken(
                OpenedRuntimeOpenExecution opened,
                Object issuerIdentity,
                long generation) {
            this.opened = opened;
            this.issuerIdentity = issuerIdentity;
            this.generation = generation;
        }
    }

    /**
     * Returns data (never authority) from an exact owner-issued open execution. Recovery uses it
     * to cross-check its durable scope/epoch/route against the runtime that was actually created.
     */
    public static RuntimeCreateRequest createRequestForGenuineOpen(
            RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        return opened == null ? null : opened.request;
    }

    /** Process-local ownership generation for an exact genuine open; zero means unverified. */
    public static long ownershipGenerationForGenuineOpen(RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        return opened == null ? 0L : opened.generation;
    }

    /**
     * Claims the exact open for one recovery-closure coordinator without retaining it in a global
     * collection. The claim lives on the already-owned open execution and therefore cannot outlive
     * that execution merely because an auxiliary replay set retained it.
     */
    public static RecoveryClosureBeginToken beginRecoveryClosureClaim(
            RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (opened == null) {
            return null;
        }
        RecoveryClosureBeginToken token = new RecoveryClosureBeginToken(
                opened,
                ISSUER_IDENTITY,
                opened.generation);
        return opened.recoveryClosureClaim.compareAndSet(null, token) ? token : null;
    }

    /** Commits one exact session identity. A committed token can never be released or reused. */
    public static boolean commitRecoveryClosureClaim(
            RecoveryClosureBeginToken token,
            RuntimeOpenExecution execution,
            Object sessionIdentity) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (!isGenuineRecoveryClosureToken(token, opened) || sessionIdentity == null) {
            return false;
        }
        synchronized (token) {
            if (opened.recoveryClosureClaim.get() != token
                    || token.state.get() != RecoveryClosureClaimState.CLAIMED) {
                return false;
            }
            token.committedSessionIdentity = sessionIdentity;
            if (!token.state.compareAndSet(
                    RecoveryClosureClaimState.CLAIMED,
                    RecoveryClosureClaimState.COMMITTED)) {
                token.committedSessionIdentity = null;
                return false;
            }
            return true;
        }
    }

    /** Rolls back only the exact still-uncommitted token returned to the failed begin path. */
    public static boolean rollbackRecoveryClosureClaim(
            RecoveryClosureBeginToken token,
            RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (!isGenuineRecoveryClosureToken(token, opened)) {
            return false;
        }
        synchronized (token) {
            if (opened.recoveryClosureClaim.get() != token
                    || !token.state.compareAndSet(
                            RecoveryClosureClaimState.CLAIMED,
                            RecoveryClosureClaimState.ROLLED_BACK)) {
                return false;
            }
            return opened.recoveryClosureClaim.compareAndSet(token, null);
        }
    }

    /** Exact committed token/session verifier used by every later recovery operation. */
    public static boolean isCommittedRecoveryClosureClaim(
            RecoveryClosureBeginToken token,
            RuntimeOpenExecution execution,
            Object sessionIdentity) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        return isGenuineRecoveryClosureToken(token, opened)
                && opened.recoveryClosureClaim.get() == token
                && token.state.get() == RecoveryClosureClaimState.COMMITTED
                && token.committedSessionIdentity == sessionIdentity;
    }

    private static boolean isGenuineRecoveryClosureToken(
            RecoveryClosureBeginToken token,
            OpenedRuntimeOpenExecution opened) {
        return token != null
                && opened != null
                && token.getClass() == RecoveryClosureBeginToken.class
                && token.issuerIdentity == ISSUER_IDENTITY
                && token.opened == opened
                && token.generation > 0L
                && token.generation == opened.generation;
    }

    public static RuntimeSubmitExecution submit(
            ProbeStateMachine machine,
            RuntimeSubmitAuthorization authorization,
            RuntimeOpenExecution execution) {
        return submitInternal(machine, authorization, execution, null, false);
    }

    /** Recovery-only dependency entry carrying the exact submitted image object unchanged. */
    public static RuntimeSubmitExecution submitWithInput(
            ProbeStateMachine machine,
            RuntimeSubmitAuthorization authorization,
            RuntimeOpenExecution execution,
            Object input) {
        if (input == null) {
            return null;
        }
        return submitInternal(machine, authorization, execution, input, true);
    }

    private static RuntimeSubmitExecution submitInternal(
            ProbeStateMachine machine,
            RuntimeSubmitAuthorization authorization,
            RuntimeOpenExecution execution,
            Object input,
            boolean inputRequired) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (opened == null || machine == null || authorization == null
                || (inputRequired && input == null)
                || opened.machine != machine || !opened.resource.ownsMachine(machine)) {
            return null;
        }
        SubmitRuntimeCommand command = machine.consumeSubmitAuthorization(authorization);
        if (command == null) {
            return null;
        }
        if (!opened.result.getRouteKey().equals(command.getKey().getRouteKey())
                || PoseRuntimeContractKt.boundaryOpenedIdentityValue(opened.result) !=
                        PoseRuntimeContractKt.boundarySubmitIdentityValue(command)) {
            return opened.resource.recordSubmitFailure(
                    command,
                    SubmissionFailure.RUNTIME_IDENTITY_MISMATCH);
        }

        return opened.resource.submit(command, input);
    }

    /** Exact-class and issuer verifier used before the state gate admits submit evidence. */
    public static boolean isGenuineSubmitExecution(RuntimeSubmitExecution execution) {
        if (execution == null || execution.getClass() != RecordedRuntimeSubmitExecution.class) {
            return false;
        }
        RecordedRuntimeSubmitExecution recorded = (RecordedRuntimeSubmitExecution) execution;
        boolean base = recorded.issuerIdentity == ISSUER_IDENTITY
                && recorded.generation > 0L
                && recorded.resource != null
                && recorded.machine != null
                && recorded.resource.generation == recorded.generation
                && recorded.resource.issuerIdentity == ISSUER_IDENTITY
                && recorded.resource.ownsMachine(recorded.machine)
                && recorded.command.getKey().equals(recorded.result.getKey());
        if (!base || recorded.submissionIdentity == null) {
            return base;
        }
        OwnedRuntimeSubmission submission = recorded.submissionIdentity;
        return submission.issuerIdentity == ISSUER_IDENTITY
                && submission.generation == recorded.generation
                && submission.command == recorded.command
                && submission.execution == recorded;
    }

    public static boolean isGenuineSubmitExecutionForMachine(
            RuntimeSubmitExecution execution,
            ProbeStateMachine machine) {
        if (!isGenuineSubmitExecution(execution)) {
            return false;
        }
        RecordedRuntimeSubmitExecution recorded = (RecordedRuntimeSubmitExecution) execution;
        return recorded.machine == machine && recorded.resource.ownsMachine(machine);
    }

    /** Exact open/execution/input verifier used by the recovery frame graph after dependency return. */
    public static boolean isGenuineSubmitExecutionForInput(
            RuntimeSubmitExecution execution,
            RuntimeOpenExecution openExecution,
            Object input) {
        if (!isGenuineSubmitExecution(execution) || input == null) {
            return false;
        }
        OpenedRuntimeOpenExecution opened = genuineOpened(openExecution);
        RecordedRuntimeSubmitExecution recorded = (RecordedRuntimeSubmitExecution) execution;
        OwnedRuntimeSubmission submission = recorded.submissionIdentity;
        return opened != null
                && submission != null
                && recorded.resource == opened.resource
                && recorded.machine == opened.machine
                && recorded.generation == opened.generation
                && submission.input == input
                && submission.execution == recorded;
    }

    /** Claims only an output object and callback instance observed by the wrapped native port. */
    public static RuntimeCallbackOutputExecution claimCallbackOutput(
            RuntimeOpenExecution openExecution,
            RuntimeResultCallback callback,
            Object callbackOutput) {
        OpenedRuntimeOpenExecution opened = genuineOpened(openExecution);
        if (opened == null || callback == null || callbackOutput == null) {
            return null;
        }
        OwnedRuntimeCallbackOutput record =
                opened.resource.claimCallbackOutput(callback, callbackOutput);
        if (record == null || record.submission.execution == null) {
            return null;
        }
        return new RecordedRuntimeCallbackOutputExecution(
                record,
                opened,
                ISSUER_IDENTITY,
                opened.generation);
    }

    /** One-shot exact-submission/output verifier; a copied callback or independent image fails. */
    public static boolean consumeCallbackOutputForSubmission(
            RuntimeCallbackOutputExecution callbackExecution,
            RuntimeSubmitExecution submitExecution,
            RuntimeOpenExecution openExecution,
            Object submittedInput,
            Object callbackOutput) {
        if (callbackExecution == null
                || callbackExecution.getClass() != RecordedRuntimeCallbackOutputExecution.class
                || !isGenuineSubmitExecutionForInput(
                        submitExecution,
                        openExecution,
                        submittedInput)) {
            return false;
        }
        OpenedRuntimeOpenExecution opened = genuineOpened(openExecution);
        RecordedRuntimeSubmitExecution submitted =
                (RecordedRuntimeSubmitExecution) submitExecution;
        RecordedRuntimeCallbackOutputExecution callback =
                (RecordedRuntimeCallbackOutputExecution) callbackExecution;
        OwnedRuntimeCallbackOutput record = callback.record;
        return opened != null
                && callback.issuerIdentity == ISSUER_IDENTITY
                && callback.opened == opened
                && callback.generation == opened.generation
                && record.issuerIdentity == ISSUER_IDENTITY
                && record.generation == opened.generation
                && record.submission == submitted.submissionIdentity
                && record.submission.input == submittedInput
                && record.output == callbackOutput
                && callback.consumed.compareAndSet(false, true);
    }

    public static RuntimeCloseExecution close(
            ProbeCloseClaim claim,
            RuntimeOpenExecution execution) {
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (opened == null || claim == null) {
            return null;
        }
        CloseRuntimeCommand command = opened.machine.authorizeCloseAttempt(claim);
        if (command == null) {
            return null;
        }
        final RuntimeCloseResult result;
        if (!opened.result.getRouteKey().equals(command.getRouteKey())) {
            result = closeFailure(command, RuntimeCloseFailure.RUNTIME_ROUTE_MISMATCH);
        } else if (PoseRuntimeContractKt.boundaryOpenedIdentityValue(opened.result)
                != PoseRuntimeContractKt.boundaryCloseIdentityValue(command)) {
            result = closeFailure(command, RuntimeCloseFailure.RUNTIME_IDENTITY_MISMATCH);
        } else {
            result = opened.resource.close(command);
        }
        return new RecordedRuntimeCloseExecution(
                command,
                result,
                opened.resource,
                opened.machine,
                claim,
                opened,
                ISSUER_IDENTITY,
                opened.generation);
    }

    public static boolean isGenuineCloseExecutionForMachine(
            RuntimeCloseExecution execution,
            ProbeStateMachine machine,
            ProbeCloseClaim claim) {
        if (execution == null || execution.getClass() != RecordedRuntimeCloseExecution.class) {
            return false;
        }
        RecordedRuntimeCloseExecution recorded = (RecordedRuntimeCloseExecution) execution;
        return recorded.issuerIdentity == ISSUER_IDENTITY
                && recorded.generation > 0L
                && recorded.machine == machine
                && recorded.claim == claim
                && recorded.opened.machine == machine
                && recorded.opened.resource == recorded.resource
                && recorded.resource.generation == recorded.generation
                && recorded.resource.ownsMachine(machine)
                && recorded.command.getRouteKey().equals(recorded.result.getRouteKey())
                && PoseRuntimeContractKt.boundaryCloseIdentityValue(recorded.command)
                        == PoseRuntimeContractKt.boundaryCloseResultIdentityValue(recorded.result);
    }

    /** Exact open/close identity verifier for a recovery closure session. */
    public static boolean isGenuineCloseExecutionForOpen(
            RuntimeCloseExecution execution,
            RuntimeOpenExecution openExecution,
            ProbeStateMachine machine,
            ProbeCloseClaim claim) {
        if (!isGenuineCloseExecutionForMachine(execution, machine, claim)) {
            return false;
        }
        OpenedRuntimeOpenExecution opened = genuineOpened(openExecution);
        RecordedRuntimeCloseExecution recorded = (RecordedRuntimeCloseExecution) execution;
        return opened != null
                && recorded.opened == opened
                && recorded.resource == opened.resource
                && recorded.generation == opened.generation
                && recorded.machine == opened.machine;
    }

    public static OrphanRuntimeCloseExecution closeOrphan(
            ProbeRoutePlanner planner,
            OrphanRuntimeCleanup requestedCleanup) {
        if (planner == null || requestedCleanup == null) {
            return null;
        }
        OrphanRuntimeCleanup cleanup = planner.authorizeOrphanClose(requestedCleanup);
        if (cleanup == null) {
            return null;
        }
        CloseRuntimeCommand command = cleanup.getCommand();
        RuntimeOpenExecution execution = cleanup.getExecution();
        OpenedRuntimeOpenExecution opened = genuineOpened(execution);
        if (opened == null) {
            return null;
        }
        OrphanCloseAuthorityImpl authority = new OrphanCloseAuthorityImpl(
                opened.resource,
                planner,
                cleanup,
                ISSUER_IDENTITY,
                opened.generation);
        final RuntimeCloseResult result;
        if (!opened.result.getRouteKey().equals(command.getRouteKey())) {
            result = closeFailure(command, RuntimeCloseFailure.RUNTIME_ROUTE_MISMATCH);
        } else if (PoseRuntimeContractKt.boundaryOpenedIdentityValue(opened.result)
                != PoseRuntimeContractKt.boundaryCloseIdentityValue(command)) {
            result = closeFailure(command, RuntimeCloseFailure.RUNTIME_IDENTITY_MISMATCH);
        } else {
            result = opened.resource.close(command);
        }
        return new RecordedOrphanRuntimeCloseExecution(
                command,
                result,
                opened.resource,
                opened,
                authority,
                ISSUER_IDENTITY,
                opened.generation);
    }

    public static boolean isGenuineOrphanCloseExecution(
            OrphanRuntimeCloseExecution execution,
            ProbeRoutePlanner planner,
            OrphanRuntimeCleanup cleanup) {
        if (execution == null
                || execution.getClass() != RecordedOrphanRuntimeCloseExecution.class) {
            return false;
        }
        RecordedOrphanRuntimeCloseExecution recorded =
                (RecordedOrphanRuntimeCloseExecution) execution;
        OrphanCloseAuthorityImpl authority = recorded.authority;
        return authority != null
                && authority.issuerIdentity == ISSUER_IDENTITY
                && authority.planner == planner
                && authority.cleanup == cleanup
                && authority.resource == recorded.resource
                && authority.generation == recorded.generation
                && recorded.issuerIdentity == ISSUER_IDENTITY
                && recorded.generation == recorded.opened.generation
                && recorded.resource == recorded.opened.resource
                && recorded.command.getRouteKey().equals(recorded.result.getRouteKey())
                && PoseRuntimeContractKt.boundaryCloseIdentityValue(recorded.command)
                        == PoseRuntimeContractKt.boundaryCloseResultIdentityValue(recorded.result);
    }

    private static OpenedRuntimeOpenExecution genuineOpened(RuntimeOpenExecution execution) {
        if (execution == null || execution.getClass() != OpenedRuntimeOpenExecution.class) {
            return null;
        }
        OpenedRuntimeOpenExecution opened = (OpenedRuntimeOpenExecution) execution;
        if (opened.issuerIdentity != ISSUER_IDENTITY
                || opened.generation <= 0L
                || opened.generation != opened.resource.generation
                || opened.resource.issuerIdentity != ISSUER_IDENTITY
                || !opened.resource.ownsMachine(opened.machine)
                || opened.resource.openedExecution != opened) {
            return null;
        }
        return opened;
    }

    private static OwnedRuntimeResource ownedResource(CreationBinding binding) {
        return new OwnedRuntimeResource(
                binding.runtime,
                binding.submissionPort,
                binding.inputSubmissionPort,
                binding.issuerIdentity,
                binding.generation);
    }

    private static RuntimeOpenExecution failedOpen(
            RuntimeRouteKey routeKey,
            RuntimeOpenFailure reason) {
        return new FailedRuntimeOpenExecution(new RuntimeOpenResult.Failed(routeKey, reason));
    }

    private static RuntimeCloseResult closeFailure(
            CloseRuntimeCommand command,
            RuntimeCloseFailure reason) {
        return PoseRuntimeContractKt.boundaryCloseFailed(command, reason);
    }

    private static Long allocateGeneration() {
        while (true) {
            long current = NEXT_GENERATION.get();
            if (current <= 0L || current == Long.MAX_VALUE) {
                return null;
            }
            if (NEXT_GENERATION.compareAndSet(current, current + 1L)) {
                return current;
            }
        }
    }

    private static RuntimeCreateCleanupOutcome combineCleanup(
            RuntimeCreateCleanupOutcome first,
            RuntimeCreateCleanupOutcome second) {
        if (first == RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN
                || second == RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN) {
            return RuntimeCreateCleanupOutcome.RESOURCE_UNCERTAIN;
        }
        if (first == RuntimeCreateCleanupOutcome.TIMED_OUT
                || second == RuntimeCreateCleanupOutcome.TIMED_OUT) {
            return RuntimeCreateCleanupOutcome.TIMED_OUT;
        }
        if (first == RuntimeCreateCleanupOutcome.CALL_THREW
                || second == RuntimeCreateCleanupOutcome.CALL_THREW) {
            return RuntimeCreateCleanupOutcome.CALL_THREW;
        }
        if (first == RuntimeCreateCleanupOutcome.REPORTED_FAILURE
                || second == RuntimeCreateCleanupOutcome.REPORTED_FAILURE) {
            return RuntimeCreateCleanupOutcome.REPORTED_FAILURE;
        }
        return RuntimeCreateCleanupOutcome.CLEAN;
    }
}
