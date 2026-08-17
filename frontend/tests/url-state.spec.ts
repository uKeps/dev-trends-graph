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

test("Click on a filter pill updates the URL", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "30D" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("days")).toBe("30");
});

test("Clicking back returns to the previous URL", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("tab", { name: "30D" }).click();
  await expect.poll(() => new URL(page.url()).searchParams.get("days")).toBe("30");
  await page.goBack();
  await expect.poll(() => new URL(page.url()).searchParams.get("days")).toBeNull();
});
