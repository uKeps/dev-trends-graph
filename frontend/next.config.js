/** @type {import('next').NextConfig} */
const nextConfig = {
  // Allow @xyflow/react CSS imports.
  transpilePackages: ["@xyflow/react"],

  // Public environment variables (exposed to the browser).
  // Configure NEXT_PUBLIC_API_URL in the Vercel dashboard.
  env: {
    NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL,
  },
};

module.exports = nextConfig;
