import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Motion Arcade · Physical QA Desk",
  description:
    "Motion Arcade의 자동화로 증명할 수 없는 실기기·사람 관찰만 수집하는 QA 포털",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
