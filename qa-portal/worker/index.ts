/** Cloudflare Worker entry point for the anonymous, stateless QA portal. */
import handler from "vinext/server/app-router-entry";

interface Env {
  ASSETS: Fetcher;
}

interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

function contentSecurityPolicy(nonce: string): string {
  return [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'`,
    "style-src 'self'",
    "img-src 'none'",
    "font-src 'self'",
    "connect-src 'self'",
    "media-src 'none'",
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
    "frame-src 'none'",
    "form-action https://github.com",
    "manifest-src 'self'",
    "worker-src 'none'",
  ].join("; ");
}

function secureResponse(response: Response, policy: string): Response {
  const headers = new Headers(response.headers);
  headers.set("Content-Security-Policy", policy);
  headers.set("X-Content-Type-Options", "nosniff");
  headers.set("X-Frame-Options", "DENY");
  headers.set("Referrer-Policy", "no-referrer");
  headers.set(
    "Permissions-Policy",
    "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()",
  );
  headers.set("Cross-Origin-Opener-Policy", "same-origin");
  headers.set("Cross-Origin-Resource-Policy", "same-origin");

  const contentType = headers.get("content-type")?.toLowerCase() ?? "";
  if (contentType.startsWith("text/html") || contentType.startsWith("text/x-component")) {
    headers.set("Cache-Control", "no-store");
  }

  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

const worker = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const nonce = crypto.randomUUID().replaceAll("-", "");
    const policy = contentSecurityPolicy(nonce);
    const requestHeaders = new Headers(request.headers);

    // Vinext reads the request CSP and applies its nonce to every framework bootstrap
    // script. The same policy is then returned to the browser below.
    requestHeaders.set("Content-Security-Policy", policy);
    const securedRequest = new Request(request, { headers: requestHeaders });
    const response = await handler.fetch(securedRequest, env, ctx);

    return secureResponse(response, policy);
  },
};

export default worker;
