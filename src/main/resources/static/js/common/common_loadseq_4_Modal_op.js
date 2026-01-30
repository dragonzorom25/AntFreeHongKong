/**
 * ===============================================================
 * 🧩 common_loadseq_4_Modal_op.js (v1.1 - 멀티 모달 및 ESC FIX)
 * --------------------------------------------------------
 * ✅ 공통 모달 관리 (열기 / 닫기 / 초기화)
 * ✅ FIX: closeModal 시 열린 다른 모달 확인 후 body 잠금 해제
 * ✅ FIX: ESC 키 입력 시 최상위 모달만 닫도록 수정
 * --------------------------------------------------------
 */

/**
 * 모달 열기
 * @param {string} modalId - "#addModal" 형태
 * @param {function} [callback] - 모달 열릴 때 실행할 콜백
 */
function openModal(modalId, callback) {
  const modal = document.querySelector(modalId);
  if (!modal) {
    console.error(`모달을 찾을 수 없습니다: ${modalId}`);
    return;
  }
  modal.style.display = "flex";        // ✅ block → flex (정중앙 정렬)
  document.body.classList.add("modal-open"); // ✅ body 잠금
  if (callback) callback();
}

/**
 * 모달 닫기
 * @param {string} modalId - "#addModal" 형태
 */
function closeModal(modalId) {
  const modal = document.querySelector(modalId);
  if (!modal) return;
  modal.style.display = "none";

  // 🚩 FIX: 현재 화면에 열려있는 다른 모달이 없는지 확인 후 body 잠금 해제
  const openModals = document.querySelectorAll(".modal");
  let stillOpen = false;
  openModals.forEach(m => {
      // display: flex 상태의 모달이 하나라도 남아있으면 true
      if (m.style.display === "flex") {
          stillOpen = true;
      }
  });

  if (!stillOpen) {
      document.body.classList.remove("modal-open"); // ✅ 열린 모달이 없으면 해제
  }
}

/**
 * 모달 내의 입력폼 초기화
 * @param {string} modalId - "#addModal" 형태
 */
function resetModalForm(modalId) {
  const modal = document.querySelector(modalId);
  if (!modal) return;
  const inputs = modal.querySelectorAll("input, textarea, select");
  inputs.forEach(el => {
    if (el.type === "checkbox" || el.type === "radio") el.checked = false;
    else el.value = "";
  });
}

/**
 * 모든 모달에 대한 전역 이벤트 등록 (1회)
 */
function initGlobalModalEvents() {
  document.addEventListener("click", (e) => {
    const target = e.target;

    // ✅ 닫기 버튼
    if (target.matches("[data-close]")) {
      const modalId = "#" + target.dataset.close;
      closeModal(modalId);
      return; // 이벤트 전파 방지
    }

    // ✅ 배경 클릭 시 닫기
    const modal = target.closest(".modal");
    if (modal && target === modal) {
      closeModal("#" + modal.id);
    }
  });

  // ✅ ESC 키로 닫기
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      // 🚩 FIX: 최상위(마지막) 모달만 닫기
      const openModals = Array.from(document.querySelectorAll(".modal")).filter(m => m.style.display === "flex");
      
      if (openModals.length > 0) {
        // 배열의 마지막 요소(가장 최근에 열린 모달)를 닫습니다.
        const topModal = openModals[openModals.length - 1];
        closeModal("#" + topModal.id); // ✅ 수정된 closeModal 함수를 호출하여 body 잠금 해제 로직 활용
      }
    }
  });
}

// ✅ 중복 방지
if (!window._modalEventBound) {
  initGlobalModalEvents();
  window._modalEventBound = true;
}