import { expect, test } from "@playwright/test";

test("topic page renders summary, sparkline and articles without JS", async ({ browser }) => {
  const topicId = "1";

  // Block all client JS so the page must work as plain HTML.
  const context = await browser.newContext({ javaScriptEnabled: false });
  const page = await context.newPage();

  await page.route("**/api/v1/graph*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        nodes: [
          {
            id: topicId,
            label: "LangGraph",
            category: "Framework",
            hypeScore: 4.5,
            mentionCount: 12,
            summary: "LangGraph is a framework for building stateful LLM workflows.",
          },
        ],
        edges: [],
        meta: { days: 30, nodeCount: 1, edgeCount: 0, generatedAt: "2026-01-01T00:00:00Z" },
      }),
    }),
  );
  await page.route("**/api/v1/nodes/" + topicId + "/summary*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        summary: "LangGraph composes graphs of LLM calls with persistent state.",
        cached: true,
        sourceUrl: "https://news.ycombinator.com/item?id=1",
        sourceTitle: "LangGraph in production",
        sourcePlatform: "hackernews",
      }),
    }),
  );
  await page.route("**/api/v1/nodes/" + topicId + "/history*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: topicId,
        days: 30,
        points: Array.from({ length: 7 }, (_, i) => ({
          ts: new Date(2026, 0, i + 1).toISOString(),
          mentionCount: i % 3,
          hypeScore: 1.0 + 0.5 * (i + 1),
        })),
        generatedAt: "2026-01-01T00:00:00Z",
      }),
    }),
  );
  await page.route("**/api/v1/nodes/" + topicId + "/articles*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        nodeId: topicId,
        articles: [
          {
            title: "LangGraph in production",
            url: "https://news.ycombinator.com/item?id=1",
            platform: "hackernews",
            publishedAt: "2026-01-01T00:00:00Z",
            nodeLabel: "LangGraph",
            nodeCategory: "Framework",
          },
        ],
        count: 1,
        generatedAt: "2026-01-01T00:00:00Z",
      }),
    }),
  );

  await page.goto(`/topic/${topicId}`);
  await expect(page.getByRole("heading", { name: "LangGraph" })).toBeVisible();
  await expect(page.getByText("composes graphs of LLM calls")).toBeVisible();
  await expect(page.getByRole("img", { name: /Mentions per day/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Recent mentions" })).toBeVisible();
  await expect(page.getByText("LangGraph in production")).toBeVisible();

  // OG metadata is present in the head even with JS disabled.
  const ogImage = await page.locator('meta[property="og:image"]').first().getAttribute("content");
  expect(ogImage).toContain(`/api/og?nodeId=${topicId}`);

  await context.close();
});

test("topic page returns 404 when the node does not exist", async ({ page }) => {
  const topicId = "missing";
  await page.route("**/api/v1/graph*", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ nodes: [], edges: [], meta: { days: 30, nodeCount: 0, edgeCount: 0, generatedAt: "2026-01-01T00:00:00Z" } }),
    }),
  );
  const response = await page.goto(`/topic/${topicId}`);
  expect(response?.status()).toBe(404);
});
