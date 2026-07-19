"use client";

import { useState } from "react";

type CopyState = "idle" | "copied" | "manual";

export function CopyButton({ label, value }: { label: string; value: string }) {
  const [state, setState] = useState<CopyState>("idle");
  const visibleLabel =
    state === "copied" ? "복사됨" : state === "manual" ? "직접 선택" : "복사";

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setState("copied");
    } catch {
      setState("manual");
    }
  }

  return (
    <button
      className="copy-button"
      type="button"
      onClick={copy}
      aria-label={`${label} ${visibleLabel}`}
    >
      <span aria-live="polite">{visibleLabel}</span>
    </button>
  );
}
