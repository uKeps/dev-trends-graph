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
  Model:     { bg: "#1e112a", border: "#a855f7", text: "#e9d5ff", glow: "rgba(168,85,247,0.5)", badgeBg: "rgba(168,85,247,0.2)" },
  Framework: { bg: "#062319", border: "#10b981", text: "#a7f3d0", glow: "rgba(16,185,129,0.5)", badgeBg: "rgba(16,185,129,0.2)" },
  Tool:      { bg: "#16230a", border: "#84cc16", text: "#d9f99d", glow: "rgba(132,204,22,0.4)",  badgeBg: "rgba(132,204,22,0.2)"  },
  Language:  { bg: "#170f38", border: "#6366f1", text: "#c7d2fe", glow: "rgba(99,102,241,0.5)",  badgeBg: "rgba(99,102,241,0.2)"  },
  Platform:  { bg: "#0b192e", border: "#0284c7", text: "#bae6fd", glow: "rgba(2,132,199,0.5)",  badgeBg: "rgba(2,132,199,0.2)"  },
  Concept:   { bg: "#271507", border: "#f59e0b", text: "#fde68a", glow: "rgba(245,158,11,0.5)", badgeBg: "rgba(245,158,11,0.2)" },
  Company:   { bg: "#260a0a", border: "#ef4444", text: "#fecaca", glow: "rgba(239,68,68,0.5)",  badgeBg: "rgba(239,68,68,0.2)"  },
  Technology:{ bg: "#061f26", border: "#06b6d4", text: "#c5f6fa", glow: "rgba(6,182,212,0.5)",  badgeBg: "rgba(6,182,212,0.2)"  },
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

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE: Nó customizado
// ─────────────────────────────────────────────────────────────────────────────

function TechNode({ data }: { data: ApiNode & { isHighlighted?: boolean } }) {
  const colors = CATEGORY_COLORS[data.category] ?? CATEGORY_COLORS.default;
  const isDimmed = data.isHighlighted === false;

  return (
    <div
      style={{
        background: colors.bg,
        border: `2px solid ${colors.border}`,
        borderRadius: "12px",
        padding: "12px 16px",
        minWidth: "170px",
        maxWidth: "220px",
        boxShadow: isDimmed ? "none" : `0 0 16px ${colors.glow}, 0 4px 12px rgba(0,0,0,0.6)`,
        opacity: isDimmed ? 0.25 : 1,
        cursor: "pointer",
        transition: "all 0.25s ease",
        fontFamily: "'Inter', sans-serif",
        position: "relative",
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="source" position={Position.Bottom} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="target" position={Position.Left} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.border, width: 8, height: 8 }} />

      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "6px" }}>
        <span
          style={{
            fontSize: "10px",
            fontWeight: 700,
            letterSpacing: "0.06em",
            textTransform: "uppercase",
            color: colors.border,
            background: colors.badgeBg,
            padding: "2px 6px",
            borderRadius: "4px",
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
          fontSize: "14px",
          fontWeight: 700,
          color: colors.text,
          lineHeight: 1.3,
          wordBreak: "break-word",
        }}
      >
        {data.label}
      </div>

      <div style={{ marginTop: "8px", fontSize: "10px", color: "#94a3b8", display: "flex", justifyContent: "space-between" }}>
        <span>Menções: {data.mentionCount}×</span>
        <span>📚 Para estudo</span>
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
  
  // Filtros de UI
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedCategory, setSelectedCategory] = useState<string>("ALL");
  const [minHype, setMinHype] = useState<number>(1.0);
  const [viewMode, setViewMode] = useState<"graph" | "cards">("graph");

  // ── Layout Clusterizado por Categoria (Organizado e Espaçado) ──────────────
  const layoutNodesByCluster = useCallback((apiNodes: ApiNode[]): Node[] => {
    if (apiNodes.length === 0) return [];

    // Agrupa por categoria
    const categoryClusters: Record<string, ApiNode[]> = {};
    apiNodes.forEach((n) => {
      const cat = n.category || "Technology";
      if (!categoryClusters[cat]) categoryClusters[cat] = [];
      categoryClusters[cat].push(n);
    });

    const categoryKeys = Object.keys(categoryClusters);
    const clusterPositions: Record<string, { x: number; y: number }> = {
      Model:     { x: 0,    y: 0 },
      Framework: { x: 750,  y: 0 },
      Tool:      { x: 1500, y: 0 },
      Language:  { x: 0,    y: 650 },
      Platform:  { x: 750,  y: 650 },
      Concept:   { x: 1500, y: 650 },
      Company:   { x: 2250, y: 0 },
      Technology:{ x: 2250, y: 650 },
    };

    const resultNodes: Node[] = [];

    categoryKeys.forEach((catKey, catIdx) => {
      const clusterNodes = categoryClusters[catKey];
      // Posição base do cluster ou cálculo em grid
      const basePos = clusterPositions[catKey] || {
        x: (catIdx % 3) * 750,
        y: Math.floor(catIdx / 3) * 650,
      };

      // Dispoe os nós dentro do cluster em uma mini-grade espaçada (2 colunas)
      const cols = 2;
      clusterNodes.forEach((node, idx) => {
        const col = idx % cols;
        const row = Math.floor(idx / cols);

        const x = basePos.x + col * 260;
        const y = basePos.y + row * 160;

        resultNodes.push({
          id: node.id,
          type: "techNode",
          position: { x, y },
          data: node,
          draggable: true,
        });
      });
    });

    return resultNodes;
  }, []);

  // ── Formata as arestas para React Flow ────────────────────────────────────
  const buildEdges = useCallback((apiEdges: ApiEdge[]): Edge[] => {
    return apiEdges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.relationType,
      type: "bezier",
      animated: e.weight > 1,
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: RELATION_COLORS[e.relationType] ?? "#64748b",
        width: 14,
        height: 14,
      },
      style: {
        stroke: RELATION_COLORS[e.relationType] ?? "#64748b",
        strokeWidth: Math.min(e.weight + 1, 4),
        opacity: 0.7,
      },
      labelStyle: {
        fill: "#e2e8f0",
        fontSize: 10,
        fontWeight: 600,
        fontFamily: "'Inter', sans-serif",
      },
      labelBgStyle: {
        fill: "rgba(15, 23, 42, 0.95)",
        rx: 4,
      },
      labelBgPadding: [6, 4] as [number, number],
    }));
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
      
      setNodes(layoutNodesByCluster(graphData.nodes || []));
      setEdges(buildEdges(graphData.edges || []));
    } catch (err: any) {
      setError(err.message ?? "Erro ao carregar o grafo de tendências.");
    } finally {
      setLoading(false);
    }
  }, [layoutNodesByCluster, buildEdges, setNodes, setEdges]);

  useEffect(() => {
    fetchGraphData(days);
  }, [days, fetchGraphData]);

  // ── Aplicar Filtros (Busca, Categoria, Hype) ──────────────────────────────
  const filteredApiNodes = useMemo(() => {
    return rawApiNodes.filter((node) => {
      const matchesSearch = searchQuery === "" || node.label.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesCat = selectedCategory === "ALL" || node.category === selectedCategory;
      const matchesHype = node.hypeScore >= minHype;
      return matchesSearch && matchesCat && matchesHype;
    });
  }, [rawApiNodes, searchQuery, selectedCategory, minHype]);

  // Atualiza destaque no Grafo quando os filtros mudam
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

  // Lista de categorias presentes
  const availableCategories = useMemo(() => {
    const cats = new Set(rawApiNodes.map((n) => n.category));
    return ["ALL", ...Array.from(cats)];
  }, [rawApiNodes]);

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
      {/* ── HEADER DE CONTROLES E FILTROS ── */}
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
          gap: "12px",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", flexWrap: "wrap", gap: "12px" }}>
          {/* Título & Logo */}
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
              🎓
            </div>
            <div>
              <h1 style={{ fontSize: "16px", fontWeight: 800, margin: 0, color: "#f8fafc" }}>
                Hub de Estudos Dev & IA
              </h1>
              <p style={{ fontSize: "11px", color: "#94a3b8", margin: 0 }}>
                Tendências e Tecnologias de Aprendizado em Grafos
              </p>
            </div>
          </div>

          {/* Busca & Modos de Visualização */}
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
                  border: "1px solid rgba(255,255,255,0.1)",
                  borderRadius: "8px",
                  padding: "6px 12px",
                  color: "#f8fafc",
                  fontSize: "12px",
                  width: "180px",
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

            {/* Alternador de Modo (Grafo vs Roadmap Cards) */}
            <div style={{ display: "flex", background: "rgba(30,41,59,0.8)", borderRadius: "8px", padding: "2px", border: "1px solid rgba(255,255,255,0.1)" }}>
              <button
                onClick={() => setViewMode("graph")}
                style={{
                  padding: "4px 10px",
                  borderRadius: "6px",
                  border: "none",
                  background: viewMode === "graph" ? "#7c3aed" : "transparent",
                  color: viewMode === "graph" ? "#fff" : "#94a3b8",
                  fontSize: "12px",
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                🌐 Grafo
              </button>
              <button
                onClick={() => setViewMode("cards")}
                style={{
                  padding: "4px 10px",
                  borderRadius: "6px",
                  border: "none",
                  background: viewMode === "cards" ? "#7c3aed" : "transparent",
                  color: viewMode === "cards" ? "#fff" : "#94a3b8",
                  fontSize: "12px",
                  fontWeight: 600,
                  cursor: "pointer",
                }}
              >
                📋 Roadmap
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

            <button
              onClick={() => fetchGraphData(days)}
              style={{
                padding: "5px 10px",
                borderRadius: "8px",
                border: "1px solid rgba(255,255,255,0.15)",
                background: "rgba(255,255,255,0.05)",
                color: "#cbd5e1",
                fontSize: "11px",
                fontWeight: 600,
                cursor: "pointer",
              }}
            >
              ↻ Atualizar
            </button>
          </div>
        </div>

        {/* ── BARRA DE FILTROS POR CATEGORIA ── */}
        <div style={{ display: "flex", alignItems: "center", gap: "8px", overflowX: "auto", paddingBottom: "2px" }}>
          <span style={{ fontSize: "11px", color: "#64748b", fontWeight: 600 }}>Filtrar Categoria:</span>
          {availableCategories.map((cat) => {
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
                {cat === "ALL" ? "✨ Todas as Categorias" : cat}
              </button>
            );
          })}

          {/* Filtro de Hype Mínimo */}
          <div style={{ marginLeft: "auto", display: "flex", alignItems: "center", gap: "6px" }}>
            <span style={{ fontSize: "11px", color: "#64748b" }}>Min Hype:</span>
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
      <div style={{ paddingTop: "100px", width: "100%", height: "100%" }}>
        {loading && (
          <div style={{ display: "flex", height: "80vh", alignItems: "center", justifyContent: "center", flexDirection: "column", gap: "12px" }}>
            <div style={{ width: "40px", height: "40px", border: "3px solid #7c3aed", borderTopColor: "transparent", borderRadius: "50%", animation: "spin 0.8s linear infinite" }} />
            <p style={{ color: "#94a3b8", fontSize: "13px" }}>Carregando materiais de estudo...</p>
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

        {/* MODO 1: GRAFO ESPAÇADO POR CLUSTERS */}
        {!loading && !error && viewMode === "graph" && (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={onNodeClick}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            minZoom={0.1}
            maxZoom={2.5}
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={32} size={1} color="rgba(255,255,255,0.05)" />
            <Controls style={{ background: "rgba(15, 23, 42, 0.9)", borderColor: "rgba(255,255,255,0.1)" }} />
            <MiniMap
              style={{ background: "rgba(15, 23, 42, 0.9)", borderColor: "rgba(255,255,255,0.1)" }}
              nodeColor={(node) => (CATEGORY_COLORS[(node.data as ApiNode)?.category] ?? CATEGORY_COLORS.default).border}
              maskColor="rgba(2, 6, 23, 0.85)"
            />
          </ReactFlow>
        )}

        {/* MODO 2: ROADMAP EM GRID DE CARDS (FÁCIL LEITURA & FILTRO) */}
        {!loading && !error && viewMode === "cards" && (
          <div style={{ padding: "24px 32px", height: "calc(100vh - 120px)", overflowY: "auto" }}>
            <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: "16px" }}>
              {filteredApiNodes.map((node) => {
                const colors = CATEGORY_COLORS[node.category] ?? CATEGORY_COLORS.default;
                return (
                  <div
                    key={node.id}
                    onClick={() => setSelectedNode(node)}
                    style={{
                      background: "rgba(15, 23, 42, 0.7)",
                      border: `1px solid ${colors.border}`,
                      borderRadius: "14px",
                      padding: "16px",
                      boxShadow: `0 4px 16px rgba(0,0,0,0.4)`,
                      cursor: "pointer",
                      transition: "transform 0.2s ease, box-shadow 0.2s ease",
                    }}
                    onMouseEnter={(e) => (e.currentTarget.style.transform = "translateY(-2px)")}
                    onMouseLeave={(e) => (e.currentTarget.style.transform = "translateY(0)")}
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
                      <span>Menções em artigos: {node.mentionCount}×</span>
                      <span style={{ color: "#a78bfa" }}>Ver Relações →</span>
                    </div>
                  </div>
                );
              })}
            </div>
            {filteredApiNodes.length === 0 && (
              <div style={{ textAlign: "center", color: "#64748b", marginTop: "40px" }}>
                Nenhuma tecnologia de estudo encontrada para os filtros selecionados.
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── PAINEL DE DETALHES DO NÓ SELECIONADO ── */}
      {selectedNode && (
        <div
          style={{
            position: "absolute",
            bottom: "20px",
            right: "20px",
            width: "280px",
            background: "rgba(15, 23, 42, 0.95)",
            backdropFilter: "blur(16px)",
            border: `1px solid ${(CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default).border}`,
            borderRadius: "16px",
            padding: "20px",
            zIndex: 30,
            boxShadow: `0 8px 32px rgba(0,0,0,0.8)`,
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
            <div>
              <span style={{ fontSize: "10px", color: (CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default).border, fontWeight: 700, textTransform: "uppercase" }}>
                {selectedNode.category}
              </span>
              <h2 style={{ fontSize: "18px", fontWeight: 800, color: "#f8fafc", margin: "4px 0 0 0" }}>
                {selectedNode.label}
              </h2>
            </div>
            <button onClick={() => setSelectedNode(null)} style={{ background: "none", border: "none", color: "#64748b", cursor: "pointer", fontSize: "20px" }}>×</button>
          </div>

          <div style={{ marginTop: "14px", display: "flex", flexDirection: "column", gap: "8px" }}>
            <div style={{ display: "flex", justifyContent: "space-between", fontSize: "12px" }}>
              <span style={{ color: "#64748b" }}>Relevância (Hype):</span>
              <span style={{ color: "#f59e0b", fontWeight: 700 }}>{selectedNode.hypeScore.toFixed(2)} 🔥</span>
            </div>
            <div style={{ display: "flex", justifyContent: "space-between", fontSize: "12px" }}>
              <span style={{ color: "#64748b" }}>Frequência no HN:</span>
              <span style={{ color: "#f8fafc", fontWeight: 600 }}>{selectedNode.mentionCount} discussões</span>
            </div>
            <div style={{ marginTop: "12px" }}>
              <a
                href={`https://google.com/search?q=${encodeURIComponent(selectedNode.label + " tutorial documentation")}`}
                target="_blank"
                rel="noreferrer"
                style={{
                  display: "block",
                  textAlign: "center",
                  padding: "8px",
                  borderRadius: "8px",
                  background: "#7c3aed",
                  color: "#fff",
                  fontSize: "12px",
                  fontWeight: 600,
                  textDecoration: "none",
                }}
              >
                📖 Pesquisar Documentação & Guia
              </a>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
