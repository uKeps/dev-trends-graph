import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: "swap",
});

export const metadata: Metadata = {
  title: "Dev Trends Graph — Mapeador de Tendências da Bolha Dev & IA",
  description:
    "Visualização interativa em grafos das principais tendências e tecnologias emergentes no ecossistema de desenvolvimento e IA, extraídas do Hacker News via LLM.",
  keywords: ["IA", "tendências tecnológicas", "grafo", "Hacker News", "desenvolvimento", "LLM"],
  authors: [{ name: "Dev Trends Graph" }],
  openGraph: {
    title: "Dev Trends Graph",
    description: "Mapeador de Tendências da Bolha Dev & IA em Grafos Interativos",
    type: "website",
  },
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="pt-BR" className={inter.variable}>
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
      </head>
      <body style={{ margin: 0, padding: 0, background: "#020617", overflow: "hidden" }}>
        {children}
      </body>
    </html>
  );
}
