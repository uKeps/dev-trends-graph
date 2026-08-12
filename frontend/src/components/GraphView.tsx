"use client";

import React, {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  type Connection,
  type NodeTypes,
  MarkerType,
  BackgroundVariant,
  Handle,
  Position,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { I18nContext, categoryLabel, useLang, useT, type Lang } from "@/lib/i18n";

// ─────────────────────────────────────────────────────────────────────────────
// TYPES
// ─────────────────────────────────────────────────────────────────────────────

interface ApiNode {
  [key: string]: unknown;
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

interface ApiEdge {
  id: string;
  source: string;
  target: string;
  sourceLabel: string;
  targetLabel: string;
  label: string;
  relationType: string;
  weight: number;
}

interface GraphData {
  nodes: ApiNode[];
  edges: ApiEdge[];
  meta?: {
    days: number;
    nodeCount: number;
    edgeCount: number;
    generatedAt: string;
  };
}

interface ApiArticle {
  title: string;
  url: string;
  platform: string;
  createdAt?: string;
  nodeLabel: string;
  nodeCategory: string;
}

const COLUMN_ORDER = ["Model", "Framework", "Tool", "Language", "Platform", "Concept"];

const SOURCE_LABELS: Record<string, string> = {
  hackernews: "Hacker News",
  reddit: "Reddit",
  devto: "Dev.to",
  lobsters: "Lobsters",
  stackoverflow: "Stack Overflow",
  web: "Web",
};

function detectPlatform(url?: string, platform?: string): string {
  if (platform) return platform;
  if (!url) return "web";
  if (url.includes("reddit.com")) return "reddit";
  if (url.includes("news.ycombinator.com")) return "hackernews";
  if (url.includes("dev.to")) return "devto";
  if (url.includes("lobste.rs")) return "lobsters";
  if (url.includes("stackoverflow.com")) return "stackoverflow";
  return "web";
}

function timeAgo(iso: string | undefined, nowLabel: string): string {
  if (!iso) return "";
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diffMs / 60000);
  if (minutes < 1) return nowLabel;
  if (minutes < 60) return `${minutes}min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

// Relevance meter (bars) — a discreet visual cue instead of a decorative icon
function Bars({ val }: { val: number }) {
  const filled = Math.min(5, Math.ceil(val / 2));
  return (
    <div className="bars">
      {Array.from({ length: 5 }).map((_, i) => (
        <i key={i} className={i < filled ? "on" : ""} />
      ))}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENT: graph node (reuses the single .card anatomy)
// ─────────────────────────────────────────────────────────────────────────────

function TechNode({ data }: { data: ApiNode & { isHighlighted?: boolean; isHovered?: boolean; onHover?: (id: string | null) => void } }) {
  const t = useT();
  const isDimmed = data.isHighlighted === false;
  const classes = ["card", "graph-card"];
  if (data.isHovered) classes.push("is-hovered");
  if (isDimmed) classes.push("is-dimmed");

  return (
    <div
      className={classes.join(" ")}
      onMouseEnter={() => data.onHover?.(data.id)}
      onMouseLeave={() => data.onHover?.(null)}
    >
      <Handle type="target" position={Position.Left} className="graph-handle" />
      <Handle type="source" position={Position.Right} className="graph-handle" />

      <div className="card-top">
        <span className="tag">{categoryLabel(t, data.category)}</span>
        <div className="meter">
          <Bars val={data.hypeScore} />
          <span className="meter-val">{data.hypeScore.toFixed(1)}</span>
        </div>
      </div>

      <div className="card-title">{data.label}</div>

      <div className="card-bottom">
        <span className="card-meta">{data.mentionCount}+ {t.discussions}</span>
        <span className="card-link">{t.details} →</span>
      </div>
    </div>
  );
}

const nodeTypes: NodeTypes = { techNode: TechNode as any };

// ─────────────────────────────────────────────────────────────────────────────
// MAIN COMPONENT: GraphView
// ─────────────────────────────────────────────────────────────────────────────

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export default function GraphView() {
  const { t, lang, changeLang } = useLang();
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [rawApiNodes, setRawApiNodes] = useState<ApiNode[]>([]);
  const [rawApiEdges, setRawApiEdges] = useState<ApiEdge[]>([]);
  const [newsArticles, setNewsArticles] = useState<ApiArticle[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState(7);
  const [selectedNode, setSelectedNode] = useState<ApiNode | null>(null);
  const [summaryLoading, setSummaryLoading] = useState<boolean>(false);
  const [summaryError, setSummaryError] = useState<boolean>(false);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);
  const [showAllEdges, setShowAllEdges] = useState<boolean>(false); // Default: no cluttered web

  // Fetch the summary on demand when selectedNode has none
  useEffect(() => {
    if (!selectedNode) {
      setSummaryLoading(false);
      setSummaryError(false);
      return;
    }

    if (selectedNode.summary && selectedNode.summary.trim().length > 0) {
      setSummaryLoading(false);
      setSummaryError(false);
      return;
    }

    setSummaryLoading(true);
    setSummaryError(false);

    fetch(`${API_BASE_URL}/api/v1/nodes/${selectedNode.id}/summary?lang=${lang}`)
      .then((res) => {
        if (!res.ok) throw new Error("Failed to fetch summary");
        return res.json();
      })
      .then((data) => {
        const hasSummary = Boolean(data?.summary && data.summary.trim().length > 0);
        const hasSourceUrl = Boolean(data?.sourceUrl);

        if (hasSummary || hasSourceUrl) {
          const patch: Partial<ApiNode> = {};
          if (hasSummary) patch.summary = data.summary.trim();
          if (data.sourceUrl) patch.sourceUrl = data.sourceUrl;
          if (data.sourceTitle) patch.sourceTitle = data.sourceTitle;
          if (data.sourcePlatform) patch.sourcePlatform = data.sourcePlatform;

          setSelectedNode((prev) => (prev && prev.id === selectedNode.id ? { ...prev, ...patch } : prev));
          setRawApiNodes((prevNodes) =>
            prevNodes.map((n) => (n.id === selectedNode.id ? { ...n, ...patch } : n))
          );
        }
        if (!hasSummary) {
          setSummaryError(true);
        }
      })
      .catch(() => {
        setSummaryError(true);
      })
      .finally(() => {
        setSummaryLoading(false);
      });
  }, [selectedNode?.id, lang]);

  // Summaries are per language: close the modal instead of showing a stale translation.
  useEffect(() => {
    setSelectedNode(null);
  }, [lang]);

  // UI filters
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");
  const [minHype, setMinHype] = useState<number>(1.0);
  const [viewMode, setViewMode] = useState<"columns" | "cards" | "news">("columns");

  // ── Column layout by category ───────────────────────────────────────────
  const layoutNodesByColumns = useCallback((apiNodes: ApiNode[], hoverId: string | null) => {
    if (apiNodes.length === 0) return [];

    const categoryMap: Record<string, ApiNode[]> = {};
    apiNodes.forEach((n) => {
      const cat = n.category || "Concept";
      if (!categoryMap[cat]) categoryMap[cat] = [];
      categoryMap[cat].push(n);
    });

    const resultNodes: Node[] = [];
    const columnWidth = 280;

    COLUMN_ORDER.forEach((catKey, colIdx) => {
      const categoryNodes = categoryMap[catKey] || [];
      categoryNodes.sort((a, b) => b.hypeScore - a.hypeScore);

      const x = colIdx * columnWidth;

      categoryNodes.forEach((node, rowIdx) => {
        const y = rowIdx * 140 + 60;

        resultNodes.push({
          id: node.id,
          type: "techNode",
          position: { x, y },
          data: {
            ...node,
            isHovered: hoverId === node.id,
            onHover: (id: string | null) => setHoveredNodeId(id),
          },
          draggable: true,
        });
      });
    });

    return resultNodes;
  }, []);

  // ── Builds the edges (single color — no per-relation color map) ─────────
  const buildEdges = useCallback((apiEdges: ApiEdge[], activeHoverId: string | null, forceShowAll: boolean): Edge[] => {
    return apiEdges
      .filter((e) => {
        if (forceShowAll) return true;
        if (activeHoverId) {
          return e.source === activeHoverId || e.target === activeHoverId;
        }
        return false;
      })
      .map((e) => {
        const isHoverConnected = activeHoverId && (e.source === activeHoverId || e.target === activeHoverId);
        return {
          id: e.id,
          source: e.source,
          target: e.target,
          label: e.relationType,
          type: "bezier",
          animated: true,
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: "#42454B",
            width: 14,
            height: 14,
          },
          style: {
            stroke: "#42454B",
            strokeWidth: isHoverConnected ? 2.5 : 1.5,
            opacity: isHoverConnected ? 1 : 0.5,
          },
          labelStyle: {
            fill: "#8C9096",
            fontSize: 10,
            fontWeight: 600,
            fontFamily: "var(--font-mono)",
          },
          labelBgStyle: {
            fill: "#161719",
            stroke: "#2A2C30",
            rx: 3,
          },
          labelBgPadding: [6, 4] as [number, number],
        };
      });
  }, []);

  // ── Fetches data from the API ─────────────────────────────────────────────
  const fetchGraphData = useCallback(async (d: number) => {
    setLoading(true);
    setError(null);
    try {
      const [graphRes, articlesRes] = await Promise.all([
        fetch(`${API_BASE_URL}/api/v1/graph?days=${d}&lang=${lang}`),
        fetch(`${API_BASE_URL}/api/v1/articles?days=${d}&limit=100`),
      ]);
      if (!graphRes.ok) throw new Error(`API error ${graphRes.status}`);

      const graphData: GraphData = await graphRes.json();
      setRawApiNodes(graphData.nodes || []);
      setRawApiEdges(graphData.edges || []);

      setNodes(layoutNodesByColumns(graphData.nodes || [], null));
      setEdges(buildEdges(graphData.edges || [], null, showAllEdges));

      if (articlesRes.ok) {
        const articlesData = await articlesRes.json();
        setNewsArticles(articlesData.articles || []);
      }
    } catch (err: any) {
      setError(err.message || "load-failed"); // "load-failed" is translated at render time
    } finally {
      setLoading(false);
    }
  }, [layoutNodesByColumns, buildEdges, setNodes, setEdges, showAllEdges, lang]);

  useEffect(() => {
    fetchGraphData(days);
  }, [days, fetchGraphData]);

  // Recomputes nodes and edges on hover or when the web toggle changes
  useEffect(() => {
    setNodes((prev) =>
      prev.map((n) => ({
        ...n,
        data: {
          ...n.data,
          isHovered: hoveredNodeId === n.id,
          onHover: (id: string | null) => setHoveredNodeId(id),
        },
      }))
    );
    setEdges(buildEdges(rawApiEdges, hoveredNodeId, showAllEdges));
  }, [hoveredNodeId, showAllEdges, rawApiEdges, buildEdges, setNodes, setEdges]);

  // ── Filters (search, category, relevance) ─────────────────────────────────
  const filteredApiNodes = useMemo(() => {
    return rawApiNodes.filter((node) => {
      const matchesSearch = searchQuery === "" || node.label.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesCat = selectedCategory === "ALL" || node.category === selectedCategory;
      const matchesHype = node.hypeScore >= minHype;
      return matchesSearch && matchesCat && matchesHype;
    });
  }, [rawApiNodes, searchQuery, selectedCategory, minHype]);

  // ── Grouping by category (curation panel of the "Columns" mode) ─────────
  const groupedByCategory = useMemo(() => {
    const map: Record<string, ApiNode[]> = {};
    filteredApiNodes.forEach((node) => {
      const cat = node.category || "Concept";
      if (!map[cat]) map[cat] = [];
      map[cat].push(node);
    });
    Object.values(map).forEach((items) => items.sort((a, b) => b.hypeScore - a.hypeScore));
    return map;
  }, [filteredApiNodes]);

  // ── Article grouping by category ("News" tab, hackertab style) ──────────
  const groupedArticlesByCategory = useMemo(() => {
    const map: Record<string, ApiArticle[]> = {};
    newsArticles.forEach((article) => {
      const cat = article.nodeCategory || "Concept";
      if (!map[cat]) map[cat] = [];
      map[cat].push(article);
    });
    Object.keys(map).forEach((cat) => {
      map[cat].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
      map[cat] = map[cat].slice(0, 6);
    });
    return map;
  }, [newsArticles]);

  useEffect(() => {
    const validIds = new Set(filteredApiNodes.map((n) => n.id));
    setNodes((prevNodes) =>
      prevNodes.map((n) => ({
        ...n,
        data: {
          ...n.data,
          isHighlighted: validIds.has(n.id),
        },
      }))
    );
  }, [filteredApiNodes, setNodes]);

  const onConnect = useCallback(
    (params: Connection) => setEdges((eds) => addEdge(params, eds)),
    [setEdges]
  );

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    setSelectedNode(node.data as ApiNode);
  }, []);

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <I18nContext.Provider value={t}>
    <div style={{ width: "100vw", height: "100vh", display: "flex", flexDirection: "column" }}>
      <header className="app-header">
        <div className="header-row">
          <div className="brand">
            <svg className="brand-mark" viewBox="0 0 30 30" fill="none">
              <path d="M15 2L27 15L15 28L3 15L15 2Z" stroke="#ECEDEE" strokeWidth="1.4" />
              <path d="M15 9L20.5 15L15 21L9.5 15L15 9Z" fill="#ECEDEE" />
            </svg>
            <div className="brand-text">
              <h1>Reticle</h1>
              <p>{t.tagline}</p>
            </div>
          </div>

          <div className="header-controls">
            <div className="search">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#55585E" strokeWidth="2">
                <circle cx="11" cy="11" r="7" /><path d="M21 21l-4.35-4.35" />
              </svg>
              <input
                type="text"
                placeholder={t.searchPlaceholder}
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
              {searchQuery && (
                <button className="search-clear" onClick={() => setSearchQuery("")}>×</button>
              )}
            </div>

            <div className="toggle-switch">
              <span>{t.connectOnHover}</span>
              <div
                className={`switch-track ${showAllEdges ? "on" : ""}`}
                onClick={() => setShowAllEdges((v) => !v)}
              >
                <div className="switch-thumb" />
              </div>
            </div>

            <div className="segmented">
              <button className={viewMode === "columns" ? "active" : ""} onClick={() => setViewMode("columns")}>{t.viewColumns}</button>
              <button className={viewMode === "cards" ? "active" : ""} onClick={() => setViewMode("cards")}>{t.viewGrid}</button>
              <button className={viewMode === "news" ? "active" : ""} onClick={() => setViewMode("news")}>{t.viewNews}</button>
            </div>

            <div className="segmented">
              {[3, 7, 14, 30].map((d) => (
                <button key={d} className={days === d ? "active" : ""} onClick={() => setDays(d)}>{d}D</button>
              ))}
            </div>

            <div className="segmented">
              {(["en", "pt"] as Lang[]).map((l) => (
                <button key={l} className={lang === l ? "active" : ""} onClick={() => changeLang(l)}>{l.toUpperCase()}</button>
              ))}
            </div>
          </div>
        </div>

        <div className="stripe" />
      </header>

      <div className="filter-row">
        <div className="filter-group">
          <span className="filter-label">{t.area}</span>
          {["ALL", ...COLUMN_ORDER].map((cat) => (
            <button
              key={cat}
              className={`pill ${selectedCategory === cat ? "active" : ""}`}
              onClick={() => setSelectedCategory(cat)}
            >
              {cat === "ALL" ? t.all : categoryLabel(t, cat)}
            </button>
          ))}
        </div>

        <div className="filter-group relevance-group">
          <span className="filter-label">{t.relevance}</span>
          {[1.0, 1.5, 2.0].map((h) => (
            <button
              key={h}
              className={`pill ${minHype === h ? "active" : ""}`}
              onClick={() => setMinHype(h)}
            >
              ≥ {h}
            </button>
          ))}
        </div>
      </div>

      <div className="board-area">
        {loading && (
          <div className="state-screen">
            <div className="spinner" />
            <p>{t.loading}</p>
          </div>
        )}

        {error && !loading && (
          <div className="state-screen">
            <p className="state-error">{error === "load-failed" ? t.loadError : error}</p>
            <button className="pill" onClick={() => fetchGraphData(days)}>{t.retry}</button>
          </div>
        )}

        {!loading && !error && viewMode === "columns" && (
          <div className="kanban">
            {COLUMN_ORDER.map((cat) => {
              const items = groupedByCategory[cat] || [];
              if (items.length === 0) return null;

              return (
                <div key={cat} className="kanban-column">
                  <div className="kanban-column-header">
                    {categoryLabel(t, cat).toUpperCase()} · {items.length}
                  </div>
                  <div className="kanban-column-body">
                    {items.map((node) => (
                      <div key={node.id} className="card" onClick={() => setSelectedNode(node)}>
                        <div className="card-top">
                          <span className="tag">{categoryLabel(t, node.category)}</span>
                          <div className="meter">
                            <Bars val={node.hypeScore} />
                            <span className="meter-val">{node.hypeScore.toFixed(1)}</span>
                          </div>
                        </div>
                        <div className="card-title">{node.label}</div>
                        <div className="card-bottom">
                          <span className="card-meta">{node.mentionCount}+ {t.discussions}</span>
                          <span className="card-link">{t.study} →</span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
            {filteredApiNodes.length === 0 && (
              <div className="empty-state">{t.emptyNodes}</div>
            )}
          </div>
        )}

        {!loading && !error && viewMode === "news" && (
          <div className="kanban">
            {COLUMN_ORDER.map((cat) => {
              const items = groupedArticlesByCategory[cat] || [];
              if (items.length === 0) return null;

              return (
                <div key={cat} className="kanban-column">
                  <div className="kanban-column-header">
                    {categoryLabel(t, cat).toUpperCase()} · {items.length}
                  </div>
                  <div className="kanban-column-body">
                    {items.map((article, idx) => {
                      const platform = detectPlatform(article.url, article.platform);
                      const platformLabel = SOURCE_LABELS[platform] ?? platform;
                      return (
                        <a
                          key={`${article.url}-${idx}`}
                          className="news-item"
                          href={article.url}
                          target="_blank"
                          rel="noreferrer"
                        >
                          <div className="news-item-top">
                            <span className="tag">{platformLabel}</span>
                            <span className="card-meta">{timeAgo(article.createdAt, t.now)}</span>
                          </div>
                          <div className="news-item-title">{article.title}</div>
                          <span className="card-meta">{article.nodeLabel}</span>
                        </a>
                      );
                    })}
                  </div>
                </div>
              );
            })}
            {newsArticles.length === 0 && (
              <div className="empty-state">{t.emptyNews}</div>
            )}
          </div>
        )}

        {!loading && !error && viewMode === "cards" && (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.2 }}
            minZoom={0.1}
            maxZoom={2.5}
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={36} size={1} color="rgba(255,255,255,0.04)" />
            <Controls />
            <MiniMap nodeColor="#42454B" maskColor="rgba(11, 12, 14, 0.85)" />
          </ReactFlow>
        )}
      </div>

      {/* ── TOPIC DETAILS MODAL ── */}
      {selectedNode && (() => {
        const platform = detectPlatform(selectedNode.sourceUrl, selectedNode.sourcePlatform);
        const platformLabel = SOURCE_LABELS[platform] ?? platform;

        return (
          <>
            <div className="modal-backdrop" onClick={() => setSelectedNode(null)} />

            <div className="modal-panel">
              <div className="modal-header">
                <div style={{ flex: 1 }}>
                  <div className="modal-tags">
                    <span className="tag">{categoryLabel(t, selectedNode.category)}</span>
                    <span className="tag">{platformLabel}</span>
                  </div>
                  <h2 className="modal-title">{selectedNode.label}</h2>
                </div>
                <button className="modal-close" onClick={() => setSelectedNode(null)}>×</button>
              </div>

              <div style={{ marginTop: "18px", display: "flex", flexDirection: "column", gap: "14px" }}>
                <div className="modal-section">
                  <div className="modal-section-label">{t.summary}</div>
                  {summaryLoading ? (
                    <p className="modal-body-text" style={{ color: "var(--text-secondary)" }}>{t.summaryLoading}</p>
                  ) : selectedNode.summary && selectedNode.summary.trim().length > 0 ? (
                    <p className="modal-body-text">{selectedNode.summary}</p>
                  ) : (
                    <p className="modal-body-text" style={{ color: "var(--text-tertiary)", fontStyle: "italic" }}>
                      {t.summaryError}
                    </p>
                  )}
                </div>

                {(selectedNode.sourceTitle || selectedNode.sourceUrl) && (
                  <div className="modal-section">
                    <div className="modal-section-label">{t.source} — {platformLabel}</div>
                    {selectedNode.sourceTitle && (
                      <p className="modal-body-text" style={{ fontWeight: 600, marginBottom: "8px" }}>
                        {selectedNode.sourceTitle}
                      </p>
                    )}
                    {selectedNode.sourceUrl && (
                      <a href={selectedNode.sourceUrl} target="_blank" rel="noreferrer" className="card-link" style={{ fontSize: "12px" }}>
                        {t.openOriginal} ↗
                      </a>
                    )}
                  </div>
                )}

                <div className="modal-metrics">
                  <div className="modal-metric">
                    <div className="modal-metric-val">
                      <div className="meter"><Bars val={selectedNode.hypeScore} /><span>{selectedNode.hypeScore.toFixed(1)}</span></div>
                    </div>
                    <div className="modal-metric-label">{t.relevance}</div>
                  </div>
                  <div className="modal-metric">
                    <div className="modal-metric-val">{selectedNode.mentionCount}</div>
                    <div className="modal-metric-label">{t.metricDiscussions}</div>
                  </div>
                </div>

                <div className="modal-actions">
                  {selectedNode.sourceUrl && (
                    <a href={selectedNode.sourceUrl} target="_blank" rel="noreferrer" className="btn btn-secondary">
                      {t.readOn} {platformLabel} ↗
                    </a>
                  )}
                </div>
              </div>
            </div>
          </>
        );
      })()}
    </div>
    </I18nContext.Provider>
  );
}
