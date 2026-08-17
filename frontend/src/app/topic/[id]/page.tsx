import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import {
  fetchNode,
  fetchNodeArticles,
  fetchNodeHistory,
  fetchNodeSummary,
  type ApiArticle,
  type ApiHistoryPoint,
  type ApiNode,
  type ApiSummary,
} from "@/lib/api";
import Sparkline from "@/components/Sparkline";

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? "https://reticle.dev";

interface PageProps {
  params: { id: string };
}

async function loadTopic(id: string): Promise<{
  node: ApiNode;
  summary: ApiSummary | null;
  history: ApiHistoryPoint[];
  articles: ApiArticle[];
} | null> {
  const node = await fetchNode(id, "en");
  if (!node) return null;
  const [summary, history, articles] = await Promise.all([
    fetchNodeSummary(id, "en"),
    fetchNodeHistory(id, 30),
    fetchNodeArticles(id, 30, 10),
  ]);
  return { node, summary, history: history ?? [], articles: articles ?? [] };
}

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const data = await loadTopic(params.id);
  if (!data) {
    return {
      title: "Topic not found — Reticle",
      robots: { index: false, follow: false },
    };
  }
  const { node, summary } = data;
  const title = `${node.label} — Reticle`;
  const description =
    summary?.summary && summary.summary.trim().length > 0
      ? summary.summary.trim().slice(0, 200)
      : `${node.label} is trending in the ${node.category.toLowerCase()} space with a relevance score of ${node.hypeScore.toFixed(1)}.`;
  return {
    title,
    description,
    keywords: [node.label, node.category, "trending", "dev trends"],
    alternates: { canonical: `${SITE_URL}/topic/${params.id}` },
    openGraph: {
      title,
      description,
      type: "article",
      url: `${SITE_URL}/topic/${params.id}`,
      images: [
        {
          url: `${SITE_URL}/api/og?nodeId=${params.id}`,
          width: 1200,
          height: 630,
          alt: `${node.label} on Reticle`,
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title,
      description,
      images: [`${SITE_URL}/api/og?nodeId=${params.id}`],
    },
  };
}

function timeAgo(iso: string | undefined): string {
  if (!iso) return "";
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diffMs / 60000);
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function platformLabel(platform: string): string {
  return {
    hackernews: "Hacker News",
    reddit: "Reddit",
    devto: "Dev.to",
    lobsters: "Lobsters",
    stackoverflow: "Stack Overflow",
  }[platform] ?? platform;
}

export default async function TopicPage({ params }: PageProps) {
  const data = await loadTopic(params.id);
  if (!data) notFound();
  const { node, summary, history, articles } = data;

  const summaryText = summary?.summary?.trim() || "";
  const hasSummary = summaryText.length > 0;
  const sourceUrl = summary?.sourceUrl?.trim() || node.sourceUrl?.trim() || "";
  const sourceTitle = summary?.sourceTitle?.trim() || node.sourceTitle?.trim() || "";
  const sourcePlatform = summary?.sourcePlatform?.trim() || node.sourcePlatform?.trim() || "web";

  return (
    <article className="topic-page">
      <header className="topic-header">
        <Link href="/" className="topic-back">Back to the trend map</Link>
        <div className="topic-tags">
          <span className="tag">{node.category}</span>
          <span className="tag">{platformLabel(sourcePlatform)}</span>
        </div>
        <h1 className="topic-title">{node.label}</h1>
        <p className="topic-tagline">
          Relevance {node.hypeScore.toFixed(1)} · {node.mentionCount} discussions
        </p>
      </header>

      <section className="topic-section" aria-labelledby="summary-heading">
        <h2 id="summary-heading" className="topic-section-label">Summary</h2>
        {hasSummary ? (
          <p className="topic-summary">{summaryText}</p>
        ) : (
          <p className="topic-summary topic-summary-muted">
            No summary has been generated for this topic yet.
          </p>
        )}
        {sourceUrl && sourceTitle && (
          <p className="topic-source">
            Source:{" "}
            <a href={sourceUrl} target="_blank" rel="noreferrer">
              {sourceTitle}
            </a>{" "}
            on {platformLabel(sourcePlatform)}
          </p>
        )}
      </section>

      {history.length > 0 && (
        <section className="topic-section" aria-labelledby="history-heading">
          <h2 id="history-heading" className="topic-section-label">
            Last 30 days
          </h2>
          <Sparkline points={history} />
        </section>
      )}

      <section className="topic-section" aria-labelledby="articles-heading">
        <h2 id="articles-heading" className="topic-section-label">
          Recent mentions
        </h2>
        {articles.length === 0 ? (
          <p className="topic-summary topic-summary-muted">
            No articles collected in the last 30 days.
          </p>
        ) : (
          <ol className="topic-articles">
            {articles.map((article, idx) => (
              <li key={`${article.url}-${idx}`} className="topic-article">
                <a href={article.url} target="_blank" rel="noreferrer" className="topic-article-title">
                  {article.title}
                </a>
                <div className="topic-article-meta">
                  <span>{platformLabel(article.platform)}</span>
                  <span aria-hidden="true">·</span>
                  <span>{timeAgo(article.publishedAt ?? article.createdAt)}</span>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>
    </article>
  );
}
