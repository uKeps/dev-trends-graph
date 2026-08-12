"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";

export const dict = {
  en: {
    tagline: "the hype, mapped daily",
    searchPlaceholder: "search_technology",
    connectOnHover: "Show every link",
    viewColumns: "Columns",
    viewGrid: "Grid",
    viewNews: "News",
    area: "Area",
    all: "All",
    relevance: "Relevance",
    loading: "Organizing study material...",
    retry: "Try again",
    loadError: "Could not load the data.",
    emptyNodes: "No technology matches the selected filters.",
    emptyNews: "No news collected yet for the selected period.",
    discussions: "discussions",
    now: "now",
    details: "Details",
    study: "Study",
    summary: "Summary",
    summaryLoading: "Loading technical summary...",
    summaryError: "The summary could not be loaded right now.",
    source: "Source",
    openOriginal: "Open original discussion",
    metricDiscussions: "Discussions",
    readOn: "Read on",
    categories: {
      Model: "Model",
      Framework: "Framework",
      Tool: "Tool",
      Language: "Language",
      Platform: "Platform",
      Concept: "Concept",
    },
  },
  pt: {
    tagline: "the hype, mapped daily",
    searchPlaceholder: "buscar_tecnologia",
    connectOnHover: "Mostrar todas as conexões",
    viewColumns: "Colunas",
    viewGrid: "Grid",
    viewNews: "Notícias",
    area: "Área",
    all: "Todas",
    relevance: "Relevância",
    loading: "Organizando materiais de estudo...",
    retry: "Tentar novamente",
    loadError: "Erro ao carregar os dados de estudo.",
    emptyNodes: "Nenhuma tecnologia encontrada para os filtros selecionados.",
    emptyNews: "Nenhuma notícia coletada ainda para o período selecionado.",
    discussions: "discussões",
    now: "agora",
    details: "Detalhes",
    study: "Estudar",
    summary: "Resumo",
    summaryLoading: "Carregando resumo técnico...",
    summaryError: "Não foi possível carregar o resumo agora.",
    source: "Fonte",
    openOriginal: "Abrir discussão original",
    metricDiscussions: "Discussões",
    readOn: "Ler no",
    categories: {
      Model: "Modelo",
      Framework: "Framework",
      Tool: "Ferramenta",
      Language: "Linguagem",
      Platform: "Plataforma",
      Concept: "Conceito",
    },
  },
} as const;

export type Lang = keyof typeof dict;
export type Dict = (typeof dict)[Lang];

/** Lets graph nodes rendered by React Flow read the active dictionary. */
export const I18nContext = createContext<Dict>(dict.en);
export const useT = () => useContext(I18nContext);

/** Category names come from the API in English; falls back to the raw value. */
export const categoryLabel = (t: Dict, category: string) =>
  (t.categories as Record<string, string>)[category] ?? category;

const HTML_LANG: Record<Lang, string> = { en: "en", pt: "pt-BR" };

/** English by default; falls back to pt-BR only for saved choice or pt browsers. */
export function useLang() {
  const [lang, setLang] = useState<Lang>("en");

  useEffect(() => {
    const saved = localStorage.getItem("lang");
    const initial: Lang =
      saved === "pt" || saved === "en"
        ? saved
        : navigator.language.toLowerCase().startsWith("pt")
        ? "pt"
        : "en";
    setLang(initial);
    document.documentElement.lang = HTML_LANG[initial];
  }, []);

  const changeLang = useCallback((next: Lang) => {
    localStorage.setItem("lang", next);
    document.documentElement.lang = HTML_LANG[next];
    setLang(next);
  }, []);

  return { t: dict[lang], lang, changeLang };
}
