/**
 * Server-side helper for talking to the Reticle API. Lives outside the React
 * tree so server components can use it without dragging the GraphView client
 * state with them.
 */

export interface ApiSummary {
  summary: string;
  cached: boolean;
  sourceUrl: string;
  sourceTitle: string;
  sourcePlatform: string;
}

export interface ApiNode {
  id: string;
  label: string;
  category: string;
  hypeScore: number;
  mentionCount: number;
  summary?: string;
  sourceUrl?: string;
  sourceTitle?: string;
  sourcePlatform?: string;
  firstSeen?: string;
  lastSeen?: string;
}

export interface ApiHistoryPoint {
  ts: string;
  mentionCount: number;
  hypeScore: number;
}

export interface ApiArticle {
  title: string;
  url: string;
  platform: string;
  publishedAt?: string;
  createdAt?: string;
  nodeLabel: string;
  nodeCategory: string;
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

async function fetchJson<T>(path: string): Promise<T | null> {
  try {
    const res = await fetch(`${API_BASE_URL}${path}`, { next: { revalidate: 300 } });
    if (!res.ok) return null;
    return (await res.json()) as T;
  } catch {
    return null;
  }
}

export async function fetchNode(id: string, lang: "en" | "pt" = "en"): Promise<ApiNode | null> {
  const data = await fetchJson<{ nodes: ApiNode[] }>(`/api/v1/graph?days=30&lang=${lang}`);
  if (!data) return null;
  return data.nodes.find((n) => n.id === id) ?? null;
}

export async function fetchNodeSummary(id: string, lang: "en" | "pt" = "en"): Promise<ApiSummary | null> {
  return fetchJson<ApiSummary>(`/api/v1/nodes/${id}/summary?lang=${lang}`);
}

export async function fetchNodeHistory(id: string, days = 30): Promise<ApiHistoryPoint[] | null> {
  const data = await fetchJson<{ points: ApiHistoryPoint[] }>(`/api/v1/nodes/${id}/history?days=${days}`);
  return data?.points ?? null;
}

export async function fetchNodeArticles(
  id: string,
  days = 30,
  limit = 10,
): Promise<ApiArticle[] | null> {
  const data = await fetchJson<{ articles: ApiArticle[] }>(
    `/api/v1/nodes/${id}/articles?days=${days}&limit=${limit}`,
  );
  return data?.articles ?? null;
}
