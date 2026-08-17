import { expect, test } from "@playwright/test";

const graphFixture = {
  nodes: [
    { id: "1", label: "LangGraph", category: "Framework", hypeScore: 4.5, mentionCount: 12 },
    { id: "2", label: "PostgreSQL", category: "Tool", hypeScore: 3.0, mentionCount: 8 },
  ],
  edges: [],
  meta: { days: 7, nodeCount: 2, edgeCount: 0, generatedAt: "2026-01-01T00:00:00Z" },
};
const articlesFixture = { articles: [], meta: { days: 7, count: 0, generatedAt: "2026-01-01T00:00:00Z" } };

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/graph*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(graphFixture) }),
  );
  await page.route("**/api/v1/articles*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(articlesFixture) }),
  );
});

test("star button toggles a node in the watchlist and survives navigation", async ({ page, context }) => {
  await page.goto("/");
  const star = page.getByRole("button", { name: /Watch LangGraph/i });
  await star.click();
  await expect(page.getByRole("button", { name: /Stop watching LangGraph/i })).toBeVisible();

  await page.goto("/watchlist");
  await expect(page.getByRole("link", { name: "LangGraph" })).toBeVisible();

  // The watchlist is per-context (per browser profile). Reload and confirm
  // the entry is still there because the state lives in localStorage.
  await page.reload();
  await expect(page.getByRole("link", { name: "LangGraph" })).toBeVisible();

  // Remove from the watchlist via the remove button on /watchlist.
  await page.getByRole("button", { name: /Stop watching LangGraph/i }).click();
  await expect(page.getByText(/Marque|Star any/)).toBeVisible();

  await context.close();
});

test("watchlist is empty by default and shows the empty-state copy", async ({ page }) => {
  await page.goto("/watchlist");
  await expect(page.getByRole("heading", { name: /Your watchlist|Sua watchlist/ })).toBeVisible();
  await expect(page.getByText(/Marque|Star any/)).toBeVisible();
});
