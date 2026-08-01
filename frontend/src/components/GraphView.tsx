"use client";

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
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

const CATEGORY_COLORS: Record<string, { bg: string; border: string; text: string; glow: string }> = {
  Language:  { bg: "#1a1050", border: "#7c3aed", text: "#c4b5fd", glow: "rgba(124,58,237,0.6)" },
  Framework: { bg: "#0d2a1a", border: "#10b981", text: "#6ee7b7", glow: "rgba(16,185,129,0.6)" },
  Tool:      { bg: "#1a2a0d", border: "#84cc16", text: "#bef264", glow: "rgba(132,204,22,0.5)"  },
  Platform:  { bg: "#0d1a2a", border: "#3b82f6", text: "#93c5fd", glow: "rgba(59,130,246,0.6)" },
  Concept:   { bg: "#2a1a0d", border: "#f59e0b", text: "#fcd34d", glow: "rgba(245,158,11,0.6)" },
  Company:   { bg: "#2a0d0d", border: "#ef4444", text: "#fca5a5", glow: "rgba(239,68,68,0.6)"  },
  Model:     { bg: "#1a0d2a", border: "#a855f7", text: "#d8b4fe", glow: "rgba(168,85,247,0.7)" },
  Technology:{ bg: "#0d1f2a", border: "#06b6d4", text: "#67e8f9", glow: "rgba(6,182,212,0.6)"  },
  default:   { bg: "#111827", border: "#4b5563", text: "#9ca3af", glow: "rgba(75,85,99,0.4)"   },
};

const RELATION_COLORS: Record<string, string> = {
  USES:             "#10b981",
  COMPETES_WITH:    "#ef4444",
  EVOLVED_FROM:     "#a855f7",
  PART_OF:          "#3b82f6",
  REPLACES:         "#f59e0b",
  INTEGRATES_WITH:  "#06b6d4",
  RUNS_ON:          "#84cc16",
  RELATED_TO:       "#6b7280",
};

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE: Nó customizado
// ─────────────────────────────────────────────────────────────────────────────

function TechNode({ data }: { data: ApiNode & { selected?: boolean } }) {
  const colors = CATEGORY_COLORS[data.category] ?? CATEGORY_COLORS.default;
  const size = Math.min(Math.max(data.hypeScore * 6, 36), 80);

  return (
    <div
      style={{
        background: colors.bg,
        border: `2px solid ${colors.border}`,
        borderRadius: "12px",
        padding: "10px 14px",
        minWidth: `${size + 40}px`,
        maxWidth: "180px",
        boxShadow: `0 0 ${size / 3}px ${colors.glow}, 0 4px 16px rgba(0,0,0,0.5)`,
        cursor: "grab",
        transition: "box-shadow 0.2s ease, transform 0.1s ease",
        fontFamily: "'Inter', sans-serif",
        position: "relative",
      }}
      onMouseEnter={(e) => {
        (e.currentTarget as HTMLDivElement).style.transform = "scale(1.05)";
        (e.currentTarget as HTMLDivElement).style.boxShadow =
          `0 0 ${size / 2}px ${colors.glow}, 0 8px 24px rgba(0,0,0,0.7)`;
      }}
      onMouseLeave={(e) => {
        (e.currentTarget as HTMLDivElement).style.transform = "scale(1)";
        (e.currentTarget as HTMLDivElement).style.boxShadow =
          `0 0 ${size / 3}px ${colors.glow}, 0 4px 16px rgba(0,0,0,0.5)`;
      }}
    >
      <Handle type="target" position={Position.Top} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="source" position={Position.Bottom} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="target" position={Position.Left} style={{ background: colors.border, width: 8, height: 8 }} />
      <Handle type="source" position={Position.Right} style={{ background: colors.border, width: 8, height: 8 }} />

      {/* Badge de categoria */}
      <div
        style={{
          fontSize: "9px",
          fontWeight: 700,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          color: colors.border,
          marginBottom: "4px",
          opacity: 0.85,
        }}
      >
        {data.category}
      </div>

      {/* Label principal */}
      <div
        style={{
          fontSize: "13px",
          fontWeight: 700,
          color: colors.text,
          lineHeight: 1.3,
          wordBreak: "break-word",
        }}
      >
        {data.label}
      </div>

      {/* Hype score */}
      <div
        style={{
          marginTop: "6px",
          display: "flex",
          alignItems: "center",
          gap: "6px",
        }}
      >
        <div
          style={{
            height: "3px",
            flex: 1,
            borderRadius: "2px",
            background: `linear-gradient(90deg, ${colors.border} ${Math.min(data.hypeScore * 10, 100)}%, rgba(255,255,255,0.1) 0%)`,
          }}
        />
        <span style={{ fontSize: "10px", color: colors.text, opacity: 0.7 }}>
          {data.hypeScore.toFixed(1)}🔥
        </span>
      </div>
    </div>
  );
}

const nodeTypes: NodeTypes = { techNode: TechNode as any };

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE: Painel lateral de tendências
// ─────────────────────────────────────────────────────────────────────────────

function TrendsPanel({ trends }: { trends: ApiNode[] }) {
  return (
    <div
      style={{
        position: "absolute",
        top: "16px",
        right: "16px",
        width: "220px",
        background: "rgba(10, 10, 20, 0.9)",
        backdropFilter: "blur(16px)",
        border: "1px solid rgba(255,255,255,0.08)",
        borderRadius: "16px",
        padding: "16px",
        zIndex: 10,
        fontFamily: "'Inter', sans-serif",
        boxShadow: "0 8px 32px rgba(0,0,0,0.6)",
      }}
    >
      <h3
        style={{
          fontSize: "12px",
          fontWeight: 700,
          letterSpacing: "0.1em",
          textTransform: "uppercase",
          color: "#a78bfa",
          marginBottom: "12px",
          display: "flex",
          alignItems: "center",
          gap: "6px",
        }}
      >
        🔥 Top Tendências
      </h3>
      {trends.slice(0, 8).map((node, i) => {
        const colors = CATEGORY_COLORS[node.category] ?? CATEGORY_COLORS.default;
        return (
          <div
            key={node.id}
            style={{
              display: "flex",
              alignItems: "center",
              gap: "8px",
              marginBottom: "8px",
              padding: "6px 8px",
              borderRadius: "8px",
              background: "rgba(255,255,255,0.03)",
              border: "1px solid rgba(255,255,255,0.05)",
            }}
          >
            <span
              style={{
                fontSize: "10px",
                fontWeight: 700,
                color: "#6b7280",
                width: "16px",
                flexShrink: 0,
              }}
            >
              #{i + 1}
            </span>
            <span
              style={{
                width: "8px",
                height: "8px",
                borderRadius: "50%",
                background: colors.border,
                flexShrink: 0,
                boxShadow: `0 0 6px ${colors.glow}`,
              }}
            />
            <span
              style={{ fontSize: "11px", color: "#e5e7eb", fontWeight: 600, flex: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
            >
              {node.label}
            </span>
            <span style={{ fontSize: "10px", color: "#f59e0b", flexShrink: 0 }}>
              {node.hypeScore.toFixed(1)}
            </span>
          </div>
        );
      })}
    </div>
  );
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPONENTE PRINCIPAL: GraphView
// ─────────────────────────────────────────────────────────────────────────────

const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export default function GraphView() {
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState(7);
  const [trends, setTrends] = useState<ApiNode[]>([]);
  const [selectedNode, setSelectedNode] = useState<ApiNode | null>(null);
  const [stats, setStats] = useState({ nodeCount: 0, edgeCount: 0 });

  // ── Posicionamento automático em layout de anéis expansivos / espiral ──────
  const layoutNodes = useCallback((apiNodes: ApiNode[]): Node[] => {
    const count = apiNodes.length;
    if (count === 0) return [];

    // Agrupa ou ordena por hypeScore para colocar nós mais relevantes no centro/anéis internos
    const sorted = [...apiNodes].sort((a, b) => b.hypeScore - a.hypeScore);

    // Configuração de anéis concêntricos
    const nodesPerRing = 12; // Máximo de nós por anel antes de abrir outro anel

    return sorted.map((n, i) => {
      const ringIndex = Math.floor(i / nodesPerRing); // 0 = anel interno, 1 = médio, 2 = externo...
      const nodeInRingIndex = i % nodesPerRing;
      const totalInThisRing = Math.min(nodesPerRing, count - ringIndex * nodesPerRing);

      // Raio base muito maior (cresce 300px a cada anel)
      const baseRadius = 450 + ringIndex * 320;
      
      // Offset de ângulo alternado por anel para desencontrar os nós
      const angleOffset = (ringIndex % 2 === 1) ? Math.PI / totalInThisRing : 0;
      const angle = (nodeInRingIndex / totalInThisRing) * 2 * Math.PI + angleOffset;

      const x = Math.cos(angle) * baseRadius + 700;
      const y = Math.sin(angle) * baseRadius + 450;

      return {
        id: n.id,
        type: "techNode",
        position: { x, y },
        data: n,
        draggable: true,
      };
    });
  }, []);

  // ── Formata as arestas para React Flow ────────────────────────────────────
  const buildEdges = useCallback((apiEdges: ApiEdge[]): Edge[] => {
    return apiEdges.map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.relationType,
      type: "bezier", // Curvas orgânicas suaves evitam retas/ângulos retos empilhados
      animated: e.weight > 1,
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: RELATION_COLORS[e.relationType] ?? "#6b7280",
        width: 14,
        height: 14,
      },
      style: {
        stroke: RELATION_COLORS[e.relationType] ?? "#6b7280",
        strokeWidth: Math.min(e.weight + 1, 4),
        opacity: 0.75,
      },
      labelStyle: {
        fill: "#cbd5e1",
        fontSize: 10,
        fontWeight: 600,
        fontFamily: "'Inter', sans-serif",
      },
      labelBgStyle: {
        fill: "rgba(15, 23, 42, 0.9)",
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
      const [graphRes, trendsRes] = await Promise.all([
        fetch(`${API_BASE_URL}/api/v1/graph?days=${d}`),
        fetch(`${API_BASE_URL}/api/v1/trends?limit=10`),
      ]);

      if (!graphRes.ok) throw new Error(`API retornou ${graphRes.status}`);

      const graphData: GraphData = await graphRes.json();
      const trendsData = trendsRes.ok ? await trendsRes.json() : { trends: [] };

      setNodes(layoutNodes(graphData.nodes));
      setEdges(buildEdges(graphData.edges));
      setTrends(trendsData.trends ?? []);
      setStats({
        nodeCount: graphData.meta?.nodeCount ?? graphData.nodes.length,
        edgeCount: graphData.meta?.edgeCount ?? graphData.edges.length,
      });
    } catch (err: any) {
      setError(err.message ?? "Erro desconhecido ao carregar grafo.");
    } finally {
      setLoading(false);
    }
  }, [layoutNodes, buildEdges]);

  useEffect(() => {
    fetchGraphData(days);
  }, [days, fetchGraphData]);

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
        background: "linear-gradient(135deg, #020617 0%, #0a0a1a 50%, #020617 100%)",
        position: "relative",
        fontFamily: "'Inter', sans-serif",
      }}
    >
      {/* ── Header ── */}
      <div
        style={{
          position: "absolute",
          top: 0,
          left: 0,
          right: 0,
          zIndex: 20,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "12px 20px",
          background: "rgba(2,6,23,0.8)",
          backdropFilter: "blur(12px)",
          borderBottom: "1px solid rgba(255,255,255,0.06)",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "12px" }}>
          <div style={{ fontSize: "20px" }}>🌐</div>
          <div>
            <h1 style={{ color: "#f1f5f9", fontSize: "16px", fontWeight: 800, margin: 0, letterSpacing: "-0.02em" }}>
              Dev Trends Graph
            </h1>
            <p style={{ color: "#64748b", fontSize: "11px", margin: 0 }}>
              Mapeador de Tendências da Bolha Dev & IA
            </p>
          </div>
        </div>

        {/* Filtro de dias */}
        <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
          {[3, 7, 14, 30].map((d) => (
            <button
              key={d}
              onClick={() => setDays(d)}
              style={{
                padding: "5px 12px",
                borderRadius: "8px",
                border: "1px solid",
                borderColor: days === d ? "#7c3aed" : "rgba(255,255,255,0.1)",
                background: days === d ? "rgba(124,58,237,0.2)" : "transparent",
                color: days === d ? "#c4b5fd" : "#6b7280",
                fontSize: "12px",
                fontWeight: 600,
                cursor: "pointer",
                transition: "all 0.2s ease",
              }}
            >
              {d}d
            </button>
          ))}
          <button
            onClick={() => fetchGraphData(days)}
            style={{
              padding: "5px 12px",
              borderRadius: "8px",
              border: "1px solid rgba(255,255,255,0.15)",
              background: "rgba(255,255,255,0.05)",
              color: "#9ca3af",
              fontSize: "12px",
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            ↻ Atualizar
          </button>
        </div>

        {/* Stats */}
        <div style={{ display: "flex", gap: "16px" }}>
          <div style={{ textAlign: "center" }}>
            <div style={{ color: "#7c3aed", fontSize: "18px", fontWeight: 800 }}>{stats.nodeCount}</div>
            <div style={{ color: "#6b7280", fontSize: "10px" }}>Conceitos</div>
          </div>
          <div style={{ textAlign: "center" }}>
            <div style={{ color: "#10b981", fontSize: "18px", fontWeight: 800 }}>{stats.edgeCount}</div>
            <div style={{ color: "#6b7280", fontSize: "10px" }}>Relações</div>
          </div>
        </div>
      </div>

      {/* ── Estado de Loading ── */}
      {loading && (
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 30,
            flexDirection: "column",
            gap: "16px",
          }}
        >
          <div
            style={{
              width: "48px",
              height: "48px",
              border: "3px solid rgba(124,58,237,0.2)",
              borderTopColor: "#7c3aed",
              borderRadius: "50%",
              animation: "spin 0.8s linear infinite",
            }}
          />
          <p style={{ color: "#6b7280", fontSize: "14px" }}>Carregando grafo de tendências...</p>
          <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
        </div>
      )}

      {/* ── Estado de Erro ── */}
      {error && !loading && (
        <div
          style={{
            position: "absolute",
            inset: 0,
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            zIndex: 30,
            flexDirection: "column",
            gap: "12px",
          }}
        >
          <div style={{ fontSize: "48px" }}>⚠️</div>
          <p style={{ color: "#ef4444", fontSize: "16px", fontWeight: 600 }}>Erro ao carregar dados</p>
          <p style={{ color: "#6b7280", fontSize: "13px" }}>{error}</p>
          <button
            onClick={() => fetchGraphData(days)}
            style={{
              padding: "10px 20px",
              borderRadius: "10px",
              border: "none",
              background: "linear-gradient(135deg, #7c3aed, #4f46e5)",
              color: "white",
              fontSize: "13px",
              fontWeight: 600,
              cursor: "pointer",
            }}
          >
            Tentar novamente
          </button>
        </div>
      )}

      {/* ── React Flow Canvas ── */}
      {!loading && !error && (
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
          style={{ paddingTop: "60px" }}
          proOptions={{ hideAttribution: true }}
        >
          <Background
            variant={BackgroundVariant.Dots}
            gap={24}
            size={1}
            color="rgba(255,255,255,0.05)"
          />
          <Controls
            style={{
              background: "rgba(10,10,20,0.9)",
              border: "1px solid rgba(255,255,255,0.08)",
              borderRadius: "12px",
            }}
          />
          <MiniMap
            style={{
              background: "rgba(10,10,20,0.9)",
              border: "1px solid rgba(255,255,255,0.08)",
              borderRadius: "12px",
            }}
            nodeColor={(node) => {
              const data = node.data as ApiNode;
              return (CATEGORY_COLORS[data?.category] ?? CATEGORY_COLORS.default).border;
            }}
            maskColor="rgba(2,6,23,0.8)"
          />

          {/* Painel de tendências */}
          <Panel position="top-right">
            <TrendsPanel trends={trends} />
          </Panel>

          {/* Legenda de categorias */}
          <Panel position="bottom-left">
            <div
              style={{
                background: "rgba(10,10,20,0.85)",
                backdropFilter: "blur(12px)",
                border: "1px solid rgba(255,255,255,0.06)",
                borderRadius: "12px",
                padding: "12px",
                display: "flex",
                flexWrap: "wrap",
                gap: "8px",
                maxWidth: "320px",
              }}
            >
              {Object.entries(CATEGORY_COLORS)
                .filter(([k]) => k !== "default")
                .map(([cat, c]) => (
                  <div key={cat} style={{ display: "flex", alignItems: "center", gap: "5px" }}>
                    <div
                      style={{
                        width: "8px",
                        height: "8px",
                        borderRadius: "50%",
                        background: c.border,
                        boxShadow: `0 0 5px ${c.glow}`,
                      }}
                    />
                    <span style={{ fontSize: "10px", color: "#6b7280" }}>{cat}</span>
                  </div>
                ))}
            </div>
          </Panel>
        </ReactFlow>
      )}

      {/* ── Detalhe do nó selecionado ── */}
      {selectedNode && (
        <div
          style={{
            position: "absolute",
            bottom: "16px",
            right: "16px",
            width: "260px",
            background: "rgba(10,10,20,0.95)",
            backdropFilter: "blur(16px)",
            border: `1px solid ${(CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default).border}`,
            borderRadius: "16px",
            padding: "16px",
            zIndex: 20,
            boxShadow: `0 0 24px ${(CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default).glow}`,
          }}
        >
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start" }}>
            <div>
              <div style={{ fontSize: "10px", color: (CATEGORY_COLORS[selectedNode.category] ?? CATEGORY_COLORS.default).border, fontWeight: 700, textTransform: "uppercase", letterSpacing: "0.08em" }}>
                {selectedNode.category}
              </div>
              <div style={{ fontSize: "17px", fontWeight: 800, color: "#f1f5f9", marginTop: "2px" }}>
                {selectedNode.label}
              </div>
            </div>
            <button
              onClick={() => setSelectedNode(null)}
              style={{ background: "none", border: "none", color: "#6b7280", cursor: "pointer", fontSize: "18px" }}
            >
              ×
            </button>
          </div>
          <div style={{ marginTop: "12px", display: "flex", flexDirection: "column", gap: "6px" }}>
            <div style={{ display: "flex", justifyContent: "space-between" }}>
              <span style={{ fontSize: "11px", color: "#6b7280" }}>Hype Score</span>
              <span style={{ fontSize: "11px", color: "#f59e0b", fontWeight: 700 }}>
                {selectedNode.hypeScore.toFixed(2)} 🔥
              </span>
            </div>
            <div style={{ display: "flex", justifyContent: "space-between" }}>
              <span style={{ fontSize: "11px", color: "#6b7280" }}>Menções</span>
              <span style={{ fontSize: "11px", color: "#e5e7eb", fontWeight: 600 }}>
                {selectedNode.mentionCount}×
              </span>
            </div>
            {selectedNode.firstSeen && (
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <span style={{ fontSize: "11px", color: "#6b7280" }}>Primeiro visto</span>
                <span style={{ fontSize: "11px", color: "#e5e7eb" }}>
                  {new Date(selectedNode.firstSeen).toLocaleDateString("pt-BR")}
                </span>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
