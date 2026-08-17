import { ImageResponse } from "next/og";
import { fetchNode, fetchNodeSummary } from "@/lib/api";

export const runtime = "nodejs";
export const contentType = "image/png";
export const size = { width: 1200, height: 630 };

/**
 * Dynamic Open Graph image for the topic page. Reads the same API the page
 * uses, so the preview always reflects the current state of the database.
 *
 * <p>Renders a 1200x630 PNG with the topic name, category, hype score and
 * the first line of the summary. Falls back to a generic "Topic not found"
 * card when the id is unknown so share previews never show a broken
 * image (just a 404 page).
 */
export default async function OgImage({ searchParams }: { searchParams: { nodeId?: string } }) {
  const nodeId = searchParams.nodeId;
  let label = "Topic not found";
  let category = "Reticle";
  let hype = "—";
  let mentions = "—";
  let blurb = "The technology you shared has not been collected by Reticle yet.";

  if (nodeId) {
    const node = await fetchNode(nodeId, "en");
    if (node) {
      label = node.label;
      category = node.category;
      hype = node.hypeScore.toFixed(1);
      mentions = String(node.mentionCount);
      const summary = await fetchNodeSummary(nodeId, "en");
      if (summary?.summary) {
        blurb = summary.summary.length > 220 ? summary.summary.slice(0, 217) + "..." : summary.summary;
      } else {
        blurb = `${node.label} is trending in the ${node.category.toLowerCase()} space — ${node.mentionCount} discussions tracked.`;
      }
    }
  }

  return new ImageResponse(
    (
      <div
        style={{
          width: "100%",
          height: "100%",
          display: "flex",
          flexDirection: "column",
          background: "#0b0c0e",
          color: "#ecedee",
          padding: "64px 72px",
          fontFamily: "system-ui, sans-serif",
          justifyContent: "space-between",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
          <svg width="40" height="40" viewBox="0 0 30 30" fill="none">
            <path d="M15 2L27 15L15 28L3 15L15 2Z" stroke="#ECEDEE" strokeWidth="1.4" />
            <path d="M15 9L20.5 15L15 21L9.5 15L15 9Z" fill="#ECEDEE" />
          </svg>
          <div style={{ fontSize: 24, color: "#8c9096", letterSpacing: "0.04em" }}>Reticle</div>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: "24px" }}>
          <div
            style={{
              display: "flex",
              gap: "12px",
              fontSize: 22,
              color: "#55585e",
              textTransform: "uppercase",
              letterSpacing: "0.08em",
            }}
          >
            <span>{category}</span>
            <span style={{ color: "#2a2c30" }}>·</span>
            <span>Trending now</span>
          </div>

          <div
            style={{
              fontSize: 84,
              fontWeight: 700,
              lineHeight: 1.05,
              letterSpacing: "-0.02em",
              color: "#ecedee",
              display: "flex",
            }}
          >
            {label}
          </div>

          <div
            style={{
              fontSize: 30,
              lineHeight: 1.4,
              color: "#8c9096",
              display: "flex",
            }}
          >
            {blurb}
          </div>
        </div>

        <div
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            fontSize: 24,
            color: "#8c9096",
            borderTop: "1px solid #2a2c30",
            paddingTop: "20px",
          }}
        >
          <span style={{ display: "flex", gap: "16px" }}>
            <span>Relevance {hype}</span>
            <span style={{ color: "#2a2c30" }}>·</span>
            <span>{mentions} discussions</span>
          </span>
          <span>reticle.dev</span>
        </div>
      </div>
    ),
    { ...size },
  );
}
