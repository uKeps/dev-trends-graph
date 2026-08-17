"use client";

import { useCallback, useEffect, useState } from "react";

/**
 * Two-way binding between a piece of component state and a URL search parameter.
 * On mount the value is read from the URL (or the provided default); updates are
 * pushed back to the URL via `history.replaceState` so back/forward stays clean
 * and the user can copy/share the URL with current filters intact.
 *
 * <p>Reads are passive (no re-render is forced by external URL changes other
 * than via the React state model). Two-way binding to history events would
 * require listening to `popstate`, which we don't need for Reticle: filters
 * are owned by the component that creates them.
 */
export function useUrlState<T extends string | number>(
  param: string,
  defaultValue: T,
  parse: (raw: string) => T | null = (raw) => raw as unknown as T,
  serialize: (value: T) => string = (value) => String(value),
): [T, (next: T | ((prev: T) => T)) => void] {
  const [value, setValueState] = useState<T>(() => {
    if (typeof window === "undefined") return defaultValue;
    const raw = new URLSearchParams(window.location.search).get(param);
    if (raw == null || raw === "") return defaultValue;
    const parsed = parse(raw);
    return parsed ?? defaultValue;
  });

  // Effect: after mount, re-read the URL so the state reflects deep-links
  // even though the useState initializer was suppressed by SSR. The lazy
  // setValueState (with prev === next short-circuit) avoids an infinite loop
  // when the state already matches the URL.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const readFromUrl = () => {
      const raw = new URLSearchParams(window.location.search).get(param);
      if (raw == null || raw === "") {
        setValueState((prev) => (prev === defaultValue ? prev : defaultValue));
        return;
      }
      const parsed = parse(raw);
      if (parsed === null) {
        setValueState((prev) => (prev === defaultValue ? prev : defaultValue));
        return;
      }
      setValueState((prev) => (prev === parsed ? prev : parsed));
    };
    readFromUrl();
    const onPop = () => readFromUrl();
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, [param, defaultValue, parse]);

  const [value, setValueState] = useState<T>(() => {
    if (typeof window === "undefined") return defaultValue;
    const raw = new URLSearchParams(window.location.search).get(param);
    if (raw == null || raw === "") return defaultValue;
    const parsed = parse(raw);
    return parsed ?? defaultValue;
  });

  // Effect: after mount, re-read the URL so the state reflects deep-links
  // even though the useState initializer was suppressed by SSR. The lazy
  // setValueState (with prev === next short-circuit) avoids an infinite loop
  // when the state already matches the URL.
  useEffect(() => {
    if (typeof window === "undefined") return;
    const readFromUrl = () => {
      const raw = new URLSearchParams(window.location.search).get(param);
      if (raw == null || raw === "") {
        setValueState((prev) => (prev === defaultValue ? prev : defaultValue));
        return;
      }
      const parsed = parse(raw);
      if (parsed === null) {
        setValueState((prev) => (prev === defaultValue ? prev : defaultValue));
        return;
      }
      setValueState((prev) => (prev === parsed ? prev : parsed));
    };
    readFromUrl();
    const onPop = () => readFromUrl();
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, [param, defaultValue, parse]);

  const setValue = useCallback(
    (next: T | ((prev: T) => T)) => {
      setValueState((prev) => {
        const resolved = typeof next === "function" ? (next as (p: T) => T)(prev) : next;
        if (typeof window !== "undefined") {
          const url = new URL(window.location.href);
          if (resolved === defaultValue) {
            url.searchParams.delete(param);
          } else {
            url.searchParams.set(param, serialize(resolved));
          }
          window.history.replaceState(null, "", url.toString());
        }
        return resolved;
      });
    },
    [param, defaultValue, serialize],
  );

  return [value, setValue];
}

export const URL_PARSERS = {
  int: (raw: string) => {
    const n = Number.parseInt(raw, 10);
    return Number.isFinite(n) ? n : null;
  },
  float: (raw: string) => {
    const n = Number.parseFloat(raw);
    return Number.isFinite(n) ? n : null;
  },
  oneOf: <T extends string>(...allowed: T[]) => (raw: string) =>
    (allowed as string[]).includes(raw) ? (raw as T) : null,
};