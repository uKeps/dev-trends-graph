"use client";

import { useWatchlist } from "@/lib/useWatchlist";

/**
 * Toggle button for a node card. Shows an empty star (or "+ Watch") when
 * the node is not in the watchlist, a filled star (or "Watching") when it
 * is. Stops event propagation so clicking the star does not also open the
 * detail modal on the kanban card.
 */
export default function WatchlistButton({
  id,
  label,
  category,
  size = "sm",
}: {
  id: string;
  label: string;
  category: string;
  size?: "sm" | "md";
}) {
  const { isWatched, toggle, hydrated } = useWatchlist();
  const watched = hydrated && isWatched(id);
  const onClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    toggle({ id, label, category });
  };
  return (
    <button
      type="button"
      className={`watchlist-button watchlist-button-${size} ${watched ? "is-watched" : ""}`}
      aria-pressed={watched}
      aria-label={watched ? `Stop watching ${label}` : `Watch ${label}`}
      title={watched ? "Stop watching" : "Add to watchlist"}
      onClick={onClick}
    >
      <svg width="12" height="12" viewBox="0 0 24 24" fill={watched ? "currentColor" : "none"} stroke="currentColor" strokeWidth="2" strokeLinejoin="round" aria-hidden="true">
        <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
      </svg>
    </button>
  );
}
