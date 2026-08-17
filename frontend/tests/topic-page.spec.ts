import { expect, test } from "@playwright/test";

/**
 * The topic page is a server component that fetches from the same backend the
 * rest of the app talks to. End-to-end coverage of its happy path requires a
 * running API server (or a fixture server bound to NEXT_PUBLIC_API_URL); the
 * 404 path is the only one we can exercise cleanly without one, because the
 * server fetch fails and notFound() fires.
 */
test("topic page returns 404 when the node does not exist", async ({ page }) => {
  const topicId = "missing";
  const response = await page.goto(`/topic/${topicId}`);
  expect(response?.status()).toBe(404);
});
