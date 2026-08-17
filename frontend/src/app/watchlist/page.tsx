"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useWatchlist, type WatchedNode } from "@/lib/useWatchlist";
import { useLang } from "@/lib/i18n";

interface LiveNode {
  id: string;
  label: string;
  category: string;
  hypeScore: number;
  mentionCount: number;
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export default function WatchlistPage() {
  const { items, remove, hydrated } = useWatchlist();
  const { t } = useLang();
  const [live, setLive] = useState<Record<string, LiveNode>>({});

  useEffect(() => {
    if (items.length === 0) return;
    let cancelled = false;
    fetch(`${API_BASE_URL}/api/v1/graph?days=30&lang=en`)
      .then((r) => (r.ok ? r.json() : null))
      .then((data) => {
        if (cancelled || !data?.nodes) return;
        const byId: Record<string, LiveNode> = {};
        for (const n of data.nodes as LiveNode[]) byId[n.id] = n;
        setLive(byId);
      })
      .catch(() => {
        // network down — keep whatever we have
      });
    return () => {
      cancelled = true;
    };
  }, [items.length]);

  return (
    <article className="topic-page">
      <header className="topic-header">
        <Link href="/" className="topic-back">{t.backToMap}</Link>
        <h1 className="topic-title">{t.watchlistTitle}</h1>
        <p className="topic-tagline">
          {items.length} {items.length === 1 ? "topic" : "topics"}
        </p>
      </header>

      {!hydrated && (
        <p className="topic-summary topic-summary-muted">Loading...</p>
      )}

      {hydrated && items.length === 0 && (
        <p className="topic-summary topic-summary-muted">{t.watchlistEmpty}</p>
      )}

      {hydrated && items.length > 0 && (
        <ul className="topic-articles">
          {items.map((node: WatchedNode) => {
            const snapshot = live[node.id];
            return (
              <li key={node.id} className="topic-article">
                <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
                  <Link href={`/topic/${node.id}`} className="topic-article-title" style={{ flex: 1 }}>
                    {node.label}
                  </Link>
                  <span className="tag">{node.category}</span>
                  {snapshot ? (
                    <span className="card-meta" aria-label="current hype">
                      {snapshot.hypeScore.toFixed(1)} · {snapshot.mentionCount} mentions
                    </span>
                  ) : (
                    <span className="card-meta" style={{ fontStyle: "italic" }}>offline</span>
                  )}
                  <button
                    type="button"
                    className="watchlist-button watchlist-button-md is-watched"
                    aria-label={`Stop watching ${node.label}`}
                    title="Remove from watchlist"
                    onClick={() => remove(node.id)}
                  >
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
                      <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
                    </svg>
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </article>
  );
}
