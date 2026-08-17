"use client";

import { useEffect, useRef } from "react";

/**
 * Focus trap + restoration for modal dialogs. Ported to vanilla React so the
 * app stays zero-runtime-deps for this concern. Returns a ref to attach to
 * the container element; the previous active element is restored when the
 * component unmounts.
 *
 * Escape and outside-click are NOT handled here so the caller can decide
 * whether to close on Esc and which clicks qualify as outside.
 */
export function useFocusTrap<T extends HTMLElement>(
  active: boolean,
  options: { initialFocus?: () => HTMLElement | null } = {},
) {
  const ref = useRef<T>(null);
  const optionsRef = useRef(options);
  optionsRef.current = options;

  useEffect(() => {
    if (!active) return;
    const container = ref.current;
    if (!container) return;

    const previouslyFocused = document.activeElement as HTMLElement | null;

    const focusables = () =>
      Array.from(
        container.querySelectorAll<HTMLElement>(
          'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
        ),
      );

    const initial = optionsRef.current.initialFocus?.() ?? focusables()[0];
    initial?.focus();

    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Tab") return;
      const list = focusables();
      if (list.length === 0) return;
      const first = list[0];
      const last = list[list.length - 1];
      const current = document.activeElement as HTMLElement | null;
      if (e.shiftKey && current === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && current === last) {
        e.preventDefault();
        first.focus();
      }
    };

    container.addEventListener("keydown", onKey);
    return () => {
      container.removeEventListener("keydown", onKey);
      previouslyFocused?.focus?.();
    };
  }, [active]);

  return ref;
}
