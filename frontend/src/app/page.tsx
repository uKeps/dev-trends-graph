import dynamic from "next/dynamic";

/**
 * Main page — renders the trends graph full screen.
 *
 * GraphView is loaded with `ssr: false`: it reads URL search params for its
 * initial filter state (days, category, hype, view mode, platform, language).
 * SSR would freeze those params to the defaults during hydration, so we render
 * the graph client-only to let the deep-linked URL drive the first paint.
 */
const GraphView = dynamic(() => import("@/components/GraphView"), { ssr: false });

export default function HomePage() {
  return <GraphView />;
}
