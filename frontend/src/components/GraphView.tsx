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
  Panel,
  BackgroundVariant,
  Handle,
  Position,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

// ─────────────────────────────────────────────────────────────────────────────
// TIPOS
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

// ─────────────────────────────────────────────────────────────────────────────
// CONSTANTES DE CORES POR CATEGORIA
// ─────────────────────────────────────────────────────────────────────────────

const CATEGORY_COLORS: Record<string, { bg: string; border: string; text: string; glow: string; badgeBg: string }> = {
  Model:     { bg: "#1e112a", border: "#a855f7", text: "#e9d5ff", glow: "rgba(168,85,247,0.4)", badgeBg: "rgba(168,85,247,0.2)" },
  Framework: { bg: "#062319", border: "#10b981", text: "#a7f3d0", glow: "rgba(16,185,129,0.4)", badgeBg: "rgba(16,185,129,0.2)" },
  Tool:      { bg: "#16230a", border: "#84cc16", text: "#d9f99d", glow: "rgba(132,204,22,0.4)",  badgeBg: "rgba(132,204,22,0.2)"  },
  Language:  { bg: "#170f38", border: "#6366f1", text: "#c7d2fe", glow: "rgba(99,102,241,0.4)",  badgeBg: "rgba(99,102,241,0.2)"  },
  Platform:  { bg: "#0b192e", border: "#0284c7", text: "#bae6fd", glow: "rgba(2,132,199,0.4)",  badgeBg: "rgba(2,132,199,0.2)"  },
  Concept:   { bg: "#271507", border: "#f59e0b", text: "#fde68a", glow: "rgba(245,158,11,0.4)", badgeBg: "rgba(245,158,11,0.2)" },
  Company:   { bg: "#260a0a", border: "#ef4444", text: "#fecaca", glow: "rgba(239,68,68,0.4)",  badgeBg: "rgba(239,68,68,0.2)"  },
  Technology:{ bg: "#061f26", border: "#06b6d4", text: "#c5f6fa", glow: "rgba(6,182,212,0.4)",  badgeBg: "rgba(6,182,212,0.2)"  },
  default:   { bg: "#0f172a", border: "#475569", text: "#cbd5e1", glow: "rgba(71,85,105,0.4)", badgeBg: "rgba(71,85,105,0.2)" },
};

const RELATION_COLORS: Record<string, string> = {
  USES:             "#10b981",
  COMPETES_WITH:    "#ef4444",
  EVOLVED_FROM:     "#a855f7",
  PART_OF:          "#0284c7",
  REPLACES:         "#f59e0b",
  INTEGRATES_WITH:  "#06b6d4",
  RUNS_ON:          "#84cc16",
  RELATED_TO:       "#64748b",
};

// Order de exibição das colunas no canvas
const COLUMN_ORDER = ["Model", "Framework", "Tool", "Language", "Platform", "Concept"];

const SOURCE_PLATFORMS: Record<string, { label: string; icon: string; color: string; bg: string }> = {
  hackernews:    { label: "Hacker News",  icon: "🔶", color: "#f97316", bg: "rgba(249,115,22,0.15)" },
  reddit:        { label: "Reddit",       icon: "🔴", color: "#ef4444", bg: "rgba(239,68,68,0.15)" },
  devto:         { label: "Dev.to",       icon: "💜", color: "#a78bfa", bg: "rgba(167,139,250,0.15)" },
  lobsters:      { label: "Lobsters",     icon: "🦞", color: "#f59e0b", bg: "rgba(245,158,11,0.15)" },
  stackoverflow: { label: "Stack Overflow", icon: "📚", color: "#f48024", bg: "rgba(244,128,36,0.15)" },
  web:           { label: "Web",          icon: "🌐", color: "#64748b", bg: "rgba(100,116,139,0.15)" },
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

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE: Nó customizado (Cards limpos)
// ─────────────────────────────────────────────────────────────────────────────

function TechNode({ data }: { data: ApiNode & { isHighlighted?: boolean; isHovered?: boolean; onHover?: (id: string | null) => void } }) {
  const colors = CATEGORY_COLORS[data.category] ?? CATEGORY_COLORS.default;
  const isDimmed = data.isHighlighted === false;

  return (
    <div
      onMouseEnter={() => data.onHover?.(data.id)}
      onMouseLeave={() => data.onHover?.(null)}
      style={{
        background: colors.bg,
        border: `2px solid ${colors.border}`,
        borderRadius: "14px",
        padding: "14px 18px",
        width: "240px",
        boxShadow: data.isHovered
          ? `0 0 24px ${colors.glow}, 0 8px 20px rgba(0,0,0,0.8)`
          : isDimmed
          ? "none"
          : `0 4px 14px rgba(0,0,0,0.5)`,
        opacity: isDimmed ? 0.2 : 1,
        transform: data.isHovered ? "scale(1.04)" : "scale(1)",
        cursor: "pointer",
        transition: "all 0.2s cubic-bezier(0.16, 1, 0.3, 1)",
        fontFamily: "'Inter', sans-serif",
        position: "relative",
      }}
    >
      <Handle type="target" position={Position.Left} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.border, width: 8, height: 8 }} />

      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "8px" }}>
        <span
          style={{
            fontSize: "10px",
            fontWeight: 700,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            color: colors.border,
            background: colors.badgeBg,
            padding: "3px 8px",
            borderRadius: "6px",
          }}
        >
          {data.category}
        </span>
        <span style={{ fontSize: "11px", fontWeight: 700, color: "#f59e0b" }}>
          🔥 {data.hypeScore.toFixed(1)}
        </span>
      </div>

      <div
        style={{
          fontSize: "15px",
          fontWeight: 700,
          color: colors.text,
          lineHeight: 1.3,
          wordBreak: "break-word",
        }}
      >
        {data.label}
      </div>

      <div style={{ marginTop: "10px", fontSize: "11px", color: "#94a3b8", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span>{data.mentionCount}× discussões</span>
        <span style={{ color: colors.border, fontWeight: 600, fontSize: "10px" }}>Detalhes →</span>
      </div>
    </div>
  );
}

const nodeTypes: NodeTypes = { techNode: TechNode as any };

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE PRINCIPAL: GraphView
// ─────────────────────────────────────────────────────────────────────────────

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export default function GraphView() {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [rawApiNodes, setRawApiNodes] = useState<ApiNode[]>([]);
  const [rawApiEdges, setRawApiEdges] = useState<ApiEdge[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState(7);
  const [selectedNode, setSelectedNode] = useState<ApiNode | null>(null);
  const [summaryLoading, setSummaryLoading] = useState<boolean>(false);
  const [summaryError, setSummaryError] = useState<boolean>(false);
  const [hoveredNodeId, setHoveredNodeId] = useState<string | null>(null);
  const [showAllEdges, setShowAllEdges] = useState<boolean>(false); // Padrão: sem teia poluída

  // Fetch summary under demand when selectedNode has no summary
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

    fetch(`${API_BASE_URL}/api/v1/nodes/${selectedNode.id}/summary`)
      .then((res) => {
        if (!res.ok) throw new Error("Erro ao buscar resumo");
        return res.json();
      })
      .then((data) => {
        if (data && data.summary && data.summary.trim().length > 0) {
          const patch: Partial<ApiNode> = { summary: data.summary.trim() };
          if (data.sourceUrl) patch.sourceUrl = data.sourceUrl;
          if (data.sourceTitle) patch.sourceTitle = data.sourceTitle;
          if (data.sourcePlatform) patch.sourcePlatform = data.sourcePlatform;

          setSelectedNode((prev) => (prev && prev.id === selectedNode.id ? { ...prev, ...patch } : prev));
          setRawApiNodes((prevNodes) =>
            prevNodes.map((n) => (n.id === selectedNode.id ? { ...n, ...patch } : n))
          );
        } else {
          setSummaryError(true);
        }
      })
      .catch(() => {
        setSummaryError(true);
      })
      .finally(() => {
        setSummaryLoading(false);
      });
  }, [selectedNode?.id]);

  // Filtros de UI
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");
  const [minHype, setMinHype] = useState<number>(1.0);
  const [viewMode, setViewMode] = useState<"columns" | "cards">("columns");

  // ── Layout em Colunas de Categoria (Espaçamento Rigoroso Sem Sobreposição) ──
  const layoutNodesByColumns = useCallback((apiNodes: ApiNode[], hoverId: string | null) => {
    if (apiNodes.length === 0) return [];

    // Agrupa por categoria
    const categoryMap: Record<string, ApiNode[]> = {};
    apiNodes.forEach((n) => {
      const cat = n.category || "Concept";
      if (!categoryMap[cat]) categoryMap[cat] = [];
      categoryMap[cat].push(n);
    });

    const resultNodes: Node[] = [];
    const columnWidth = 340; // Espaço horizontal seguro entre colunas

    COLUMN_ORDER.forEach((catKey, colIdx) => {
      const categoryNodes = categoryMap[catKey] || [];
      // Ordena por hype score dentro da coluna
      categoryNodes.sort((a, b) => b.hypeScore - a.hypeScore);

      const x = colIdx * columnWidth;

      categoryNodes.forEach((node, rowIdx) => {
        const y = rowIdx * 150 + 60; // 150px de distância vertical garante ZERO sobreposição

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

  // ── Formata as arestas (Exibe linhas de forma limpa ou sob hover) ─────────
  const buildEdges = useCallback((apiEdges: ApiEdge[], activeHoverId: string | null, forceShowAll: boolean): Edge[] => {
    return apiEdges
      .filter((e) => {
        if (forceShowAll) return true;
        // Se a teia estiver desativada, mostra linhas APENAS do nó que o usuário passou o mouse por cima
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
            color: RELATION_COLORS[e.relationType] ?? "#64748b",
            width: 16,
            height: 16,
          },
          style: {
            stroke: RELATION_COLORS[e.relationType] ?? "#64748b",
            strokeWidth: isHoverConnected ? 3.5 : 2,
            opacity: isHoverConnected ? 1 : 0.6,
          },
          labelStyle: {
            fill: "#f8fafc",
            fontSize: 11,
            fontWeight: 700,
            fontFamily: "'Inter', sans-serif",
          },
          labelBgStyle: {
            fill: "rgba(15, 23, 42, 0.95)",
            rx: 6,
          },
          labelBgPadding: [8, 5] as [number, number],
        };
      });
  }, []);

  // ── Busca dados da API ────────────────────────────────────────────────────
  const fetchGraphData = useCallback(async (d: number) => {
    setLoading(true);
    setError(null);
    try {
      const graphRes = await fetch(`${API_BASE_URL}/api/v1/graph?days=${d}`);
      if (!graphRes.ok) throw new Error(`API retornou status ${graphRes.status}`);

      const graphData: GraphData = await graphRes.json();
      setRawApiNodes(graphData.nodes || []);
      setRawApiEdges(graphData.edges || []);
      
      setNodes(layoutNodesByColumns(graphData.nodes || [], null));
      setEdges(buildEdges(graphData.edges || [], null, showAllEdges));
    } catch (err: any) {
      setError(err.message ?? "Erro ao carregar os dados de estudo.");
    } finally {
      setLoading(false);
    }
  }, [layoutNodesByColumns, buildEdges, setNodes, setEdges, showAllEdges]);

  useEffect(() => {
    fetchGraphData(days);
  }, [days, fetchGraphData]);

  // Recalcula nós e arestas ao passar o mouse por cima de um nó ou mudar botão de teia
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

  // ── Aplicar Filtros (Busca, Categoria, Hype) ──────────────────────────────
  const filteredApiNodes = useMemo(() => {
    return rawApiNodes.filter((node) => {
      const matchesSearch = searchQuery === "" || node.label.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesCat = selectedCategory === "ALL" || node.category === selectedCategory;
      const matchesHype = node.hypeScore >= minHype;
      return matchesSearch && matchesCat && matchesHype;
    });
  }, [rawApiNodes, searchQuery, selectedCategory, minHype]);

  // Atualiza destaque
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

  // ── Renderização ──────────────────────────────────────────────────────────
  return (
    <div
      style={{
        width: "100vw",
        height: "100vh",
        background: "#020617",
        position: "relative",
        fontFamily: "'Inter', sans-serif",
        color: "#f8fafc",
        overflow: "hidden",
      }}
    >
      {/* ── HEADER CLEAN E CONTROLES DE FILTRO ── */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          zIndex: 20,
          background: "rgba(15, 23, 42, 0.95)",
          backdropFilter: "blur(12px)",
          borderBottom: "1px solid rgba(255,255,255,0.08)",
          padding: "12px 24px",
          display: "flex",
          flexDirection: "column",
          gap: "10px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
          {/* Logo & Título */}
          <div style={{ display: "flex", alignItems: "center", gap: "10px" }}>
            <div
              style={{
                background: "linear-gradient(135deg, #7c3aed, #4f46e5)",
                width: "36px",
                height: "36px",
                borderRadius: "10px",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                fontSize: "18px",
              }}
            >
              🚀
            </div>
            <div>
              <h1 style={{ fontSize: "16px", fontWeight: 800, margin: 0, color: "#f8fafc" }}>
                Dev Trends & Study Hub
              </h1>
              <p style={{ fontSize: "11px", color: "#94a3b8", margin: 0 }}>
                HN · Reddit · Dev.to · Lobsters — clique em um tópico para ver resumo e fonte
              </p>
            </div>
          </div>

          {/* Busca & Controles principais */}
          <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
            {/* Input de Busca */}
            <div style={{ position: "relative" }}>
              <input
                type="text"
                placeholder="🔍 Buscar tecnologia..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                style={{
                  background: "rgba(30, 41, 59, 0.8)",
                  border: "1px solid rgba(255,255,255,0.12)",
                  borderRadius: "8px",
                  padding: "6px 12px",
                  color: "#f8fafc",
                  fontSize: "12px",
                  width: "200px",
                  outline: "none",
                }}
              />
              {searchQuery && (
                <button
                  onClick={() => setSearchQuery("")}
                  style={{
                    position: "absolute",
                    right: "8px",
                    top: "50%",
                    transform: "translateY(-50%)",
                    background: "none",
                    border: "none",
                    color: "#94a3b8",
                    cursor: "pointer",
                  }}
                >
                  ×
                </button>
              )}
            </div>

            {/* Alternador de Mostrar Teia / Linhas Conectadas */}
            <button
              onClick={() => setShowAllEdges(!showAllEdges)}
              style={{
                padding: "6px 12px",
                borderRadius: "8px",
                border: "1px solid",
                borderColor: showAllEdges ? "#7c3aed" : "rgba(255,255,255,0.12)",
                background: showAllEdges ? "rgba(124,58,237,0.25)" : "rgba(30, 41, 59, 0.6)",
                color: showAllEdges ? "#c4b5fd" : "#94a3b8",
                fontSize: "11px",
                fontWeight: 600,
                cursor: "pointer",
                display: "flex",
                alignItems: "center",
                gap: "6px",
              }}
            >
              {showAllEdges ? "🌐 Exibindo Conexões" : "✨ Linhas ao passar mouse"}
            </button>

            {/* Alternador de Modo de Visualização */}
            <div style={{ display: "flex", background: "rgba(30,41,59,0.8)", borderRadius: "8px", padding: "2px", border: "1px solid rgba(255,255,255,0.1)" }}>
              <button
                onClick={() => setViewMode("columns")}
                style={{
                  padding: "4px 10px",
                  borderRadius: "6px",
                  border: "none",
                  background: viewMode === "columns" ? "#7c3aed" : "transparent",
                  color: viewMode === "columns" ? "#fff" : "#94a3b8",
                  fontSize: "11px",
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                📊 Colunas
              </button>
              <button
                onClick={() => setViewMode("cards")}
                style={{
                  padding: "4px 10px",
                  borderRadius: "6px",
                  border: "none",
                  background: viewMode === "cards" ? "#7c3aed" : "transparent",
                  color: viewMode === "cards" ? "#fff" : "#94a3b8",
                  fontSize: "11px",
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                📋 Grid
              </button>
            </div>

            {/* Filtro Temporal */}
            <div style={{ display: "flex", gap: "4px" }}>
              {[3, 7, 14, 30].map((d) => (
                <button
                  key={d}
                  onClick={() => setDays(d)}
                  style={{
                    padding: "4px 8px",
                    borderRadius: "6px",
                    border: "1px solid",
                    borderColor: days === d ? "#7c3aed" : "rgba(255,255,255,0.1)",
                    background: days === d ? "rgba(124,58,237,0.3)" : "transparent",
                    color: days === d ? "#c4b5fd" : "#64748b",
                    fontSize: "11px",
                    fontWeight: 600,
                    cursor: "pointer",
                  }}
                >
                  {d}d
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* ── FILTROS DE CATEGORIA EM TAGS ── */}
        <div style={{ display: "flex", alignItems: "center", gap: "8px", overflowX: "auto" }}>
          <span style={{ fontSize: "11px", color: "#64748b", fontWeight: 600 }}>Filtrar Área:</span>
          {["ALL", ...COLUMN_ORDER].map((cat) => {
            const isSelected = selectedCategory === cat;
            const catColors = CATEGORY_COLORS[cat] ?? CATEGORY_COLORS.default;
            return (
              <button
                key={cat}
                onClick={() => setSelectedCategory(cat)}
                style={{
                  padding: "3px 10px",
                  borderRadius: "16px",
                  border: `1px solid ${isSelected ? catColors.border : "rgba(255,255,255,0.1)"}`,
                  background: isSelected ? catColors.bg : "rgba(30, 41, 59, 0.4)",
                  color: isSelected ? catColors.text : "#94a3b8",
                  fontSize: "11px",
                  fontWeight: isSelected ? 700 : 500,
                  cursor: "pointer",
                  whiteSpace: "nowrap",
                  transition: "all 0.2s ease",
                }}
              >
                {cat === "ALL" ? "✨ Todas as Áreas" : cat}
              </button>
            );
          })}

          {/* Filtro de Hype Mínimo */}
          <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: "6px" }}>
            <span style={{ fontSize: "11px", color: "#64748b" }}>Relevância:</span>
            {[1.0, 1.5, 2.0].map((h) => (
              <button
                key={h}
                onClick={() => setMinHype(h)}
                style={{
                  padding: "2px 6px",
                  borderRadius: "4px",
                  border: "1px solid",
                  borderColor: minHype === h ? "#f59e0b" : "rgba(255,255,255,0.1)",
                  background: minHype === h ? "rgba(245,158,11,0.2)" : "transparent",
                  color: minHype === h ? "#fde68a" : "#64748b",
                  fontSize: "10px",
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                ≥ {h} 🔥
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* ── CONTEÚDO PRINCIPAL ── */}
      <div style={{ paddingTop: "96px", width: "100%", height: "100%" }}>
        {loading && (
          <div style={{ display: "flex", height: "80vh", alignItems: "center", justifyContent: "center", flexDirection: "column", gap: "12px" }}>
            <div style={{ width: "40px", height: "40px", border: "3px solid #7c3aed", borderTopColor: "transparent", borderRadius: "50%", animation: "spin 0.8s linear infinite" }} />
            <p style={{ color: "#94a3b8", fontSize: "13px" }}>Organizando materiais de estudo...</p>
            <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
          </div>
        )}

        {error && !loading && (
          <div style={{ display: "flex", height: "80vh", alignItems: "center", justifyContent: "center", flexDirection: "column", gap: "12px" }}>
            <div style={{ fontSize: "40px" }}>⚠️</div>
            <p style={{ color: "#ef4444", fontSize: "15px" }}>{error}</p>
            <button onClick={() => fetchGraphData(days)} style={{ padding: "8px 16px", borderRadius: "8px", background: "#7c3aed", color: "#fff", border: "none", cursor: "pointer" }}>Tentar novamente</button>
          </div>
        )}

        {/* MODO 1: COLUNAS LIMPAS SEM SOBREPOSIÇÃO */}
        {!loading && !error && viewMode === "columns" && (
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
            <Controls style={{ background: "rgba(15, 23, 42, 0.9)", borderColor: "rgba(255,255,255,0.1)" }} />
            <MiniMap
              style={{ background: "rgba(15, 23, 42, 0.9)", borderColor: "rgba(255,255,255,0.1)" }}
              nodeColor={(node) => (CATEGORY_COLORS[(node.data as ApiNode)?.category] ?? CATEGORY_COLORS.default).border}
              maskColor="rgba(2, 6, 23, 0.85)"
            />
          </ReactFlow>
        )}

        {/* MODO 2: GRID DE CARDS (LIMPO E DIRETO) */}
        {!loading && !error && viewMode === "cards" && (
          <div style={{ padding: "24px 32px", height: "calc(100vh - 110px)", overflowY: "auto" }}>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: "16px" }}>
              {filteredApiNodes.map((node) => {
                const colors = CATEGORY_COLORS[node.category] ?? CATEGORY_COLORS.default;
                return (
                  <div
                    key={node.id}
                    onClick={() => setSelectedNode(node)}
                    style={{
                      background: "rgba(15, 23, 42, 0.75)",
                      border: `1px solid ${colors.border}`,
                      borderRadius: "14px",
                      padding: "16px",
                      boxShadow: `0 4px 14px rgba(0,0,0,0.4)`,
                      cursor: "pointer",
                      transition: "all 0.2s ease",
                    }}
                    onMouseEnter={(e) => {
                      e.currentTarget.style.transform = "translateY(-2px)";
                      e.currentTarget.style.boxShadow = `0 0 20px ${colors.glow}`;
                    }}
                    onMouseLeave={(e) => {
                      e.currentTarget.style.transform = "translateY(0)";
                      e.currentTarget.style.boxShadow = `0 4px 14px rgba(0,0,0,0.4)`;
                    }}
                  >
                    <div style={{ display: "flex", justifyContent: "space-between", marginBottom: "8px" }}>
                      <span style={{ fontSize: "10px", fontWeight: 700, color: colors.border, background: colors.badgeBg, padding: "2px 8px", borderRadius: "6px" }}>
                        {node.category}
                      </span>
                      <span style={{ fontSize: "11px", fontWeight: 700, color: "#f59e0b" }}>
                        🔥 {node.hypeScore.toFixed(1)}
                      </span>
                    </div>
                    <h3 style={{ fontSize: "16px", fontWeight: 700, color: colors.text, margin: "0 0 8px 0" }}>
                      {node.label}
                    </h3>
                    <div style={{ fontSize: "11px", color: "#94a3b8", display: "flex", justifyContent: "space-between", marginTop: "12px" }}>
                      <span>{node.mentionCount} discussões</span>
                      <span style={{ color: colors.border, fontWeight: 600 }}>Estudar →</span>
                    </div>
                  </div>
                );
              })}
            </div>
            {filteredApiNodes.length === 0 && (
              <div style={{ textAlign: "center", color: "#64748b", marginTop: "60px" }}>
                Nenhuma tecnologia encontrada para os filtros selecionados.
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── MODAL DE DETALHES DO TÓPICO ── */}
      {selectedNode && (() => {
        const colors = CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default;
        const platform = detectPlatform(selectedNode.sourceUrl, selectedNode.sourcePlatform);
        const sourceInfo = SOURCE_PLATFORMS[platform] ?? SOURCE_PLATFORMS.web;

        return (
          <>
            {/* Backdrop */}
            <div
              onClick={() => setSelectedNode(null)}
              style={{
                position: "fixed",
                inset: 0,
                background: "rgba(2, 6, 23, 0.75)",
                backdropFilter: "blur(4px)",
                zIndex: 40,
              }}
            />

            {/* Painel */}
            <div
              style={{
                position: "fixed",
                top: "50%",
                left: "50%",
                transform: "translate(-50%, -50%)",
                width: "min(480px, 92vw)",
                maxHeight: "85vh",
                overflowY: "auto",
                background: "rgba(15, 23, 42, 0.98)",
                backdropFilter: "blur(20px)",
                border: `2px solid ${colors.border}`,
                borderRadius: "20px",
                padding: "24px",
                zIndex: 50,
                boxShadow: `0 24px 60px rgba(0,0,0,0.9), 0 0 40px ${colors.glow}`,
              }}
            >
              {/* Cabeçalho */}
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: "12px" }}>
                <div style={{ flex: 1 }}>
                  <div style={{ display: "flex", gap: "8px", alignItems: "center", flexWrap: "wrap", marginBottom: "6px" }}>
                    <span style={{ fontSize: "10px", color: colors.border, fontWeight: 700, textTransform: "uppercase", background: colors.badgeBg, padding: "3px 8px", borderRadius: "6px" }}>
                      {selectedNode.category}
                    </span>
                    <span style={{ fontSize: "10px", color: sourceInfo.color, fontWeight: 700, background: sourceInfo.bg, padding: "3px 8px", borderRadius: "6px" }}>
                      {sourceInfo.icon} {sourceInfo.label}
                    </span>
                  </div>
                  <h2 style={{ fontSize: "22px", fontWeight: 800, color: "#f8fafc", margin: 0, lineHeight: 1.2 }}>
                    {selectedNode.label}
                  </h2>
                </div>
                <button
                  onClick={() => setSelectedNode(null)}
                  style={{ background: "rgba(100,116,139,0.2)", border: "none", color: "#94a3b8", cursor: "pointer", fontSize: "18px", width: "32px", height: "32px", borderRadius: "8px", flexShrink: 0 }}
                >×</button>
              </div>

              <div style={{ marginTop: "18px", display: "flex", flexDirection: "column", gap: "14px" }}>
                {/* Resumo */}
                <div
                  style={{
                    background: "rgba(30, 41, 59, 0.7)",
                    borderLeft: `3px solid ${colors.border}`,
                    padding: "14px 16px",
                    borderRadius: "10px",
                  }}
                >
                  <div style={{ fontSize: "10px", fontWeight: 700, color: "#94a3b8", marginBottom: "6px", letterSpacing: "0.05em", textTransform: "uppercase" }}>
                    Resumo
                  </div>
                  {summaryLoading ? (
                    <div style={{ fontSize: "13px", color: "#94a3b8", display: "flex", alignItems: "center", gap: "8px" }}>
                      <span>Carregando resumo técnico...</span>
                    </div>
                  ) : selectedNode.summary && selectedNode.summary.trim().length > 0 ? (
                    <p style={{ fontSize: "14px", color: "#e2e8f0", lineHeight: 1.6, margin: 0 }}>
                      {selectedNode.summary}
                    </p>
                  ) : (
                    <p style={{ fontSize: "13px", color: "#94a3b8", margin: 0, fontStyle: "italic" }}>
                      Não foi possível carregar o resumo agora.
                    </p>
                  )}
                </div>

                {/* Fonte original */}
                {(selectedNode.sourceTitle || selectedNode.sourceUrl) && (
                  <div
                    style={{
                      background: sourceInfo.bg,
                      border: `1px solid ${sourceInfo.color}40`,
                      padding: "14px 16px",
                      borderRadius: "10px",
                    }}
                  >
                    <div style={{ fontSize: "10px", fontWeight: 700, color: sourceInfo.color, marginBottom: "6px", letterSpacing: "0.05em", textTransform: "uppercase" }}>
                      {sourceInfo.icon} Fonte — {sourceInfo.label}
                    </div>
                    {selectedNode.sourceTitle && (
                      <p style={{ fontSize: "13px", color: "#f1f5f9", fontWeight: 600, margin: "0 0 8px 0", lineHeight: 1.4 }}>
                        {selectedNode.sourceTitle}
                      </p>
                    )}
                    {selectedNode.sourceUrl && (
                      <a
                        href={selectedNode.sourceUrl}
                        target="_blank"
                        rel="noreferrer"
                        style={{
                          display: "inline-flex",
                          alignItems: "center",
                          gap: "6px",
                          fontSize: "12px",
                          color: sourceInfo.color,
                          fontWeight: 600,
                          textDecoration: "none",
                        }}
                      >
                        Abrir discussão original ↗
                      </a>
                    )}
                  </div>
                )}

                {/* Métricas */}
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "10px" }}>
                  <div style={{ background: "rgba(30,41,59,0.5)", padding: "10px 14px", borderRadius: "10px", textAlign: "center" }}>
                    <div style={{ fontSize: "18px", fontWeight: 800, color: "#f59e0b" }}>{selectedNode.hypeScore.toFixed(1)} 🔥</div>
                    <div style={{ fontSize: "10px", color: "#64748b", marginTop: "2px" }}>Relevância</div>
                  </div>
                  <div style={{ background: "rgba(30,41,59,0.5)", padding: "10px 14px", borderRadius: "10px", textAlign: "center" }}>
                    <div style={{ fontSize: "18px", fontWeight: 800, color: "#f8fafc" }}>{selectedNode.mentionCount}</div>
                    <div style={{ fontSize: "10px", color: "#64748b", marginTop: "2px" }}>Discussões</div>
                  </div>
                </div>

                {/* Ações */}
                <div style={{ display: "flex", flexDirection: "column", gap: "8px" }}>
                  {selectedNode.sourceUrl && (
                    <a
                      href={selectedNode.sourceUrl}
                      target="_blank"
                      rel="noreferrer"
                      style={{
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        gap: "8px",
                        padding: "12px",
                        borderRadius: "10px",
                        background: sourceInfo.bg,
                        border: `1px solid ${sourceInfo.color}60`,
                        color: sourceInfo.color,
                        fontSize: "13px",
                        fontWeight: 700,
                        textDecoration: "none",
                      }}
                    >
                      {sourceInfo.icon} Ler no {sourceInfo.label} ↗
                    </a>
                  )}

                  <a
                    href={`https://google.com/search?q=${encodeURIComponent(selectedNode.label + " documentation tutorial github")}`}
                    target="_blank"
                    rel="noreferrer"
                    style={{
                      display: "flex",
                      alignItems: "center",
                      justifyContent: "center",
                      gap: "8px",
                      padding: "12px",
                      borderRadius: "10px",
                      background: "#7c3aed",
                      color: "#fff",
                      fontSize: "13px",
                      fontWeight: 700,
                      textDecoration: "none",
                    }}
                  >
                    📖 Documentação & Tutoriais ↗
                  </a>
                </div>
              </div>
            </div>
          </>
        );
      })()}
    </div>
  );
}
