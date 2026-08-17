import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const graphFixture = {
  nodes: [
    {
      id: "1",
      label: "LangGraph",
      category: "Framework",
      hypeScore: 4.5,
      mentionCount: 12,
      summary: "LangGraph is a framework for building stateful LLM workflows.",
      sourceTitle: "LangGraph in production",
      sourceUrl: "https://news.ycombinator.com/item?id=1",
      sourcePlatform: "hackernews",
    },
    {
      id: "2",
      label: "PostgreSQL",
      category: "Tool",
      hypeScore: 3.0,
      mentionCount: 8,
      summary: "PostgreSQL is an open source relational database.",
      sourceTitle: "Postgres tips",
      sourceUrl: "https://news.ycombinator.com/item?id=2",
      sourcePlatform: "hackernews",
    },
  ],
  edges: [
    {
      id: "e1",
      source: "1",
      target: "2",
      sourceLabel: "LangGraph",
      targetLabel: "PostgreSQL",
      label: "USES",
      relationType: "USES",
      weight: 2,
    },
  ],
  meta: { days: 7, nodeCount: 2, edgeCount: 1, generatedAt: new Date().toISOString() },
};

const articlesFixture = { articles: [], meta: { days: 7, count: 0, generatedAt: new Date().toISOString() } };

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/graph*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(graphFixture) }),
  );
  await page.route("**/api/v1/articles*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(articlesFixture) }),
  );
});

test("home page has no critical or serious a11y violations", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Reticle" })).toBeVisible();

  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();

  const blocking = results.violations.filter((v) => v.impact === "critical" || v.impact === "serious");
  expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
});

test("modal dialog has no critical or serious a11y violations", async ({ page }) => {
  await page.goto("/");
  await page.getByText("LangGraph").first().click();
  await expect(page.getByRole("dialog")).toBeVisible();

  const results = await new AxeBuilder({ page })
    .include("[role='dialog']")
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();

  const blocking = results.violations.filter((v) => v.impact === "critical" || v.impact === "serious");
  expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([]);
});
