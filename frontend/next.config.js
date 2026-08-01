/** @type {import('next').NextConfig} */
const nextConfig = {
  // Permite importação do CSS do @xyflow/react
  transpilePackages: ["@xyflow/react"],

  // Variáveis de ambiente públicas (expostas ao browser)
  // Configure NEXT_PUBLIC_API_URL no painel da Vercel
  env: {
    NEXT_PUBLIC_API_URL: process.env.NEXT_PUBLIC_API_URL,
  },

  // Desabilita telemetria
  telemetry: false,
};

module.exports = nextConfig;
