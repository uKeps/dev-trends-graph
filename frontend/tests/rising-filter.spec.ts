import { expect, test } from "@playwright/test";

const graphFixture = {
  nodes: [
    { id: "1", label: "LangGraph", category: "Framework", hypeScore: 4.5, mentionCount: 12 },
  ],
  edges: [],
  meta: { days: 7, nodeCount: 1, edgeCount: 0, generatedAt: "2026-01-01T00:00:00Z" },
};
const emptyArticles = { articles: [], meta: { days: 7, count: 0, generatedAt: "2026-01-01T00:00:00Z" } };

test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/graph*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(graphFixture) }),
  );
  await page.route("**/api/v1/articles*", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(emptyArticles) }),
  );
});

test("rising filter updates the URL and filters out non-rising nodes", async ({ page }) => {
  // The fixture has no firstSeen, so the rising filter excludes every node.
  // The filter still needs to be present in the UI and reflected in the URL.
  await page.goto("/");
  const risingPill = page.getByRole("button", { name: /Rising|Em alta/ });
  await expect(risingPill).toBeVisible();
  await risingPill.click();
  await expect.poll(() => new URL(page.url()).searchParams.get("rising")).toBe("1");
  await expect(risingPill).toHaveAttribute("aria-pressed", "true");

  // No node has firstSeen in the fixture, so the empty state is shown.
  await expect(page.getByText(/No technology|Nenhuma tecnologia/)).toBeVisible();
});
