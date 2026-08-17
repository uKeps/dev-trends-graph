import { useEffect, useState } from "react";

/**
 * Visually hidden until focused via keyboard navigation. The "Skip to content"
 * phrase is a WCAG 2.4.1 standard recognized across locales; the visible
 * label follows the active document language via {@link useDocumentLang}.
 */
export default function SkipLink() {
  const [label, setLabel] = useState("Skip to content");

  useEffect(() => {
    const update = () => {
      const lang = document.documentElement.lang;
      setLabel(lang.toLowerCase().startsWith("pt") ? "Pular para o conteúdo" : "Skip to content");
    };
    update();
    const observer = new MutationObserver(update);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["lang"] });
    return () => observer.disconnect();
  }, []);

  return (
    <a href="#main" className="skip-link">
      {label}
    </a>
  );
}
