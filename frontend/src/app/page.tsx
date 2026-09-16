import { redirect } from "next/navigation";

export default function Home() {
  // 첫 화면은 구버전과 동일하게 거시경제 매크로 지표입니다.
  redirect("/macro");
}
