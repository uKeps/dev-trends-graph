"use client";

import { useCallback, useEffect, useState } from "react";

/**
 * Per-user watchlist persisted in localStorage. Stores enough metadata per
 * node (id, label, category) that the /watchlist page can render the saved
 * list even when the graph payload is empty (e.g. cold start of the API).
 *
 * <p>SSR-safe: every accessor is wrapped in a window guard and the hook
 * returns the empty state on the server, then hydrates on mount.
 */

const STORAGE_KEY = "reticle.watchlist.v1";

export interface WatchedNode {
  id: string;
  label: string;
  category: string;
  addedAt: number;
}

function readStorage(): WatchedNode[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (n): n is WatchedNode =>
        n != null && typeof n.id === "string" && typeof n.label === "string",
    );
  } catch {
    return [];
  }
}

function writeStorage(items: WatchedNode[]) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    window.dispatchEvent(new CustomEvent("reticle:watchlist"));
  } catch {
    // Quota / private mode — silent.
  }
}

export function useWatchlist() {
  const [items, setItems] = useState<WatchedNode[]>([]);
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setItems(readStorage());
    setHydrated(true);
    const onUpdate = () => setItems(readStorage());
    window.addEventListener("reticle:watchlist", onUpdate);
    window.addEventListener("storage", onUpdate);
    return () => {
      window.removeEventListener("reticle:watchlist", onUpdate);
      window.removeEventListener("storage", onUpdate);
    };
  }, []);

  const isWatched = useCallback(
    (id: string) => items.some((n) => n.id === id),
    [items],
  );

  const add = useCallback((node: { id: string; label: string; category: string }) => {
    setItems((prev) => {
      if (prev.some((n) => n.id === node.id)) return prev;
      const next = [...prev, { ...node, addedAt: Date.now() }];
      writeStorage(next);
      return next;
    });
  }, []);

  const remove = useCallback((id: string) => {
    setItems((prev) => {
      const next = prev.filter((n) => n.id !== id);
      writeStorage(next);
      return next;
    });
  }, []);

  const toggle = useCallback(
    (node: { id: string; label: string; category: string }) => {
      if (isWatched(node.id)) remove(node.id);
      else add(node);
    },
    [isWatched, add, remove],
  );

  return { items, isWatched, add, remove, toggle, hydrated };
}
