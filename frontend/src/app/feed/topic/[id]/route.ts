import { fetchNode, fetchNodeArticles } from "@/lib/api";

export const revalidate = 300;

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "https://reticle.dev";

/**
 * Per-topic RSS 2.0 feed. Rendered server-side from the same API the
 * /topic/[id] page uses, so a reader subscribed to 'LangGraph' gets
 * every article the curator linked to that node.
 */
export async function GET(
  _req: Request,
  { params }: { params: { id: string } },
) {
  const node = await fetchNode(params.id, "en");
  if (!node) {
    return new Response("Topic not found", { status: 404 });
  }

  const articles = (await fetchNodeArticles(params.id, 90, 50)) ?? [];
  const pubDate = new Date().toUTCString();
  const buildDate = new Date().toUTCString();

  const items = articles
    .map((a) => {
      const date = a.publishedAt ?? a.createdAt ?? new Date().toISOString();
      const title = xmlEscape(a.title);
      const link = xmlEscape(a.url);
      const desc = xmlEscape(`${a.platform.toUpperCase()} · ${a.nodeLabel}`);
      return `    <item>
      <title>${title}</title>
      <link>${link}</link>
      <guid isPermaLink="true">${link}</guid>
      <pubDate>${new Date(date).toUTCString()}</pubDate>
      <description>${desc}</description>
    </item>`;
    })
    .join("\n");

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0">
  <channel>
    <title>${xmlEscape(node.label)} — Reticle</title>
    <link>${SITE_URL}/topic/${params.id}</link>
    <description>Articles mentioning ${xmlEscape(node.label)} in the dev &amp; AI bubble.</description>
    <language>en-us</language>
    <lastBuildDate>${buildDate}</lastBuildDate>
    <pubDate>${pubDate}</pubDate>
${items}
  </channel>
</rss>
`;

  return new Response(xml, {
    status: 200,
    headers: {
      "content-type": "application/rss+xml; charset=utf-8",
      "cache-control": "public, max-age=300, s-maxage=300",
    },
  });
}

function xmlEscape(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}
