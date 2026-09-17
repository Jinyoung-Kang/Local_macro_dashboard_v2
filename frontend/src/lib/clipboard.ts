/**
 * src/lib/clipboard.ts
 * 텍스트 복사 — 보안 컨텍스트가 아니어도 동작하도록.
 *
 * `navigator.clipboard`는 HTTPS이거나 localhost일 때만 존재합니다. 이 대시보드는
 * 집 안의 다른 기기에서 `http://192.168.0.x:3000`으로 여는 경우가 많은데, 거기서는
 * 객체 자체가 없어 복사 버튼이 아무 반응 없이 죽습니다. 그래서 예전 방식
 * (화면 밖 textarea + execCommand)을 대비책으로 둡니다.
 */

export async function copyText(text: string): Promise<void> {
  if (!text) {
    throw new Error("복사할 내용이 없습니다.");
  }

  if (typeof navigator !== "undefined" && navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // 객체가 있어도 권한이 거부될 수 있습니다(사용자 설정, 브라우저 정책,
      // 포커스를 잃은 탭). 여기서 포기하지 않고 아래 대비책으로 넘어갑니다.
    }
  }

  if (!fallbackCopy(text)) {
    throw new Error(
      "브라우저가 복사를 막았습니다. 아래 원본 데이터를 직접 선택해 복사하세요.",
    );
  }
}

/** 구형 경로 — 선택 영역을 만들어 복사합니다. 성공 여부를 그대로 돌려줍니다. */
function fallbackCopy(text: string): boolean {
  const area = document.createElement("textarea");
  area.value = text;
  // 화면에 보이지 않게 두되, 포커스가 가야 하므로 display:none은 쓸 수 없습니다.
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.top = "-1000px";
  area.style.opacity = "0";
  document.body.appendChild(area);

  try {
    area.select();
    area.setSelectionRange(0, text.length);
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    document.body.removeChild(area);
  }
}
