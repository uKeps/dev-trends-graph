import { expect, test } from "@playwright/test";

const graphFixture = {
  nodes: [
    { id: "1", label: "LangGraph", category: "Framework", hypeScore: 4.5, mentionCount: 12 },
    { id: "2", label: "PostgreSQL", category: "Tool", hypeScore: 3.0, mentionCount: 8 },
  ],
  edges: [],
  meta: { days: 7, nodeCount: 2, edgeCount: 0, generatedAt: new Date().toISOString() },
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

test("URL state round-trip: filters and view are restored from the query string", async ({ page }) => {
  await page.goto("/?days=14&cat=Framework&view=cards&hype=2.0");
  await expect(page.getByRole("tab", { name: "14D" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("button", { name: "Framework", exact: true })).toHaveClass(/active/);
  await expect(page.getByRole("tab", { name: "Grid" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("button", { name: "≥ 2.0" })).toHaveClass(/active/);
});

test("Click on a filter pill updates the URL", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "30D" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("days")).toBe("30");
});

test("Browser back/forward rewinds the filter state", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "7D" }).click();
  await page.getByRole("tab", { name: "30D" }).click();
  await page.goBack();
  await expect(page.getByRole("tab", { name: "7D" })).toHaveAttribute("aria-selected", "true");
});
