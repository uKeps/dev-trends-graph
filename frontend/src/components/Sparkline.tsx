import type { ApiHistoryPoint } from "@/lib/api";

/**
 * Inline-SVG sparkline of the daily mention count for a single topic.
 * Server-rendered (no client JS, no chart library). Renders nothing when
 * the series is empty or has a single point.
 */
export default function Sparkline({ points }: { points: ApiHistoryPoint[] }) {
  if (points.length < 2) return null;

  const width = 600;
  const height = 80;
  const padX = 4;
  const padY = 8;

  const counts = points.map((p) => p.mentionCount);
  const max = Math.max(1, ...counts);
  const stepX = (width - padX * 2) / (points.length - 1);

  const coords = points.map((p, i) => {
    const x = padX + i * stepX;
    const y = height - padY - (p.mentionCount / max) * (height - padY * 2);
    return [x, y] as const;
  });

  const linePath = coords
    .map(([x, y], i) => (i === 0 ? `M ${x.toFixed(1)} ${y.toFixed(1)}` : `L ${x.toFixed(1)} ${y.toFixed(1)}`))
    .join(" ");

  const areaPath = `${linePath} L ${coords[coords.length - 1][0].toFixed(1)} ${height - padY} L ${coords[0][0].toFixed(1)} ${height - padY} Z`;

  const peakIndex = counts.reduce((best, c, i) => (c > counts[best] ? i : best), 0);

  return (
    <figure className="topic-sparkline">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`Mentions per day over the last ${points.length} days, peak ${counts[peakIndex]} on day ${peakIndex + 1}`}
        preserveAspectRatio="none"
      >
        <path d={areaPath} fill="rgba(125, 130, 140, 0.18)" />
        <path d={linePath} fill="none" stroke="#ECEDEE" strokeWidth={1.5} strokeLinejoin="round" />
        <circle
          cx={coords[peakIndex][0].toFixed(1)}
          cy={coords[peakIndex][1].toFixed(1)}
          r={3}
          fill="#ECEDEE"
        />
      </svg>
      <figcaption className="topic-sparkline-caption">
        Peak {counts[peakIndex]} {counts[peakIndex] === 1 ? "mention" : "mentions"} · total {counts.reduce((a, b) => a + b, 0)}
      </figcaption>
    </figure>
  );
}
