;;; Статус требования семестра по свидетельствам (план, З5, шаг 4).
;;;
;;; Факты загружает sapsr/content/requirements.py:
;;;   requirement — требование: применимо ли на этом этапе, есть ли в записке часть, где ждём свидетельство;
;;;   judgement   — оценка фрагмента языковой моделью; verified — цитата найдена в тексте;
;;;   threshold   — порог уверенности из профиля.
;;; Если правило выводит несколько статусов, побеждает более строгий
;;; (disputed > violated > not_established > satisfied) — см. STATUS_PRIORITY в requirements.py.

(deftemplate requirement
  (slot id (type STRING))
  (slot applicable (type SYMBOL) (allowed-symbols TRUE FALSE))
  (slot part-present (type SYMBOL) (allowed-symbols TRUE FALSE)))

(deftemplate judgement
  (slot req (type STRING))
  (slot block (type STRING))
  (slot verdict (type SYMBOL) (allowed-symbols supports contradicts irrelevant))
  (slot confidence (type FLOAT))
  (slot verified (type SYMBOL) (allowed-symbols TRUE FALSE)))

(deftemplate threshold (slot value (type FLOAT)))

(deftemplate status
  (slot req (type STRING))
  (slot value (type SYMBOL))
  (slot review (type SYMBOL) (allowed-symbols TRUE FALSE))
  (slot reason (type SYMBOL)))

(defrule not-applicable
  (requirement (id ?r) (applicable FALSE))
  =>
  (assert (status (req ?r) (value not_applicable) (review FALSE) (reason stage))))

(defrule satisfied
  (requirement (id ?r) (applicable TRUE) (part-present TRUE))
  (threshold (value ?t))
  (judgement (req ?r) (verdict supports) (verified TRUE) (confidence ?c&:(>= ?c ?t)))
  (not (judgement (req ?r) (verdict contradicts) (verified TRUE) (confidence ?c2&:(>= ?c2 ?t))))
  =>
  (assert (status (req ?r) (value satisfied) (review FALSE) (reason evidence))))

(defrule violated
  (requirement (id ?r) (applicable TRUE))
  (threshold (value ?t))
  (judgement (req ?r) (verdict contradicts) (verified TRUE) (confidence ?c&:(>= ?c ?t)))
  (not (judgement (req ?r) (verdict supports) (verified TRUE) (confidence ?c2&:(>= ?c2 ?t))))
  =>
  (assert (status (req ?r) (value violated) (review FALSE) (reason evidence))))

;; Модель нашла и подтверждение, и противоречие — показываем оба, решает преподаватель.
(defrule disputed-evidence
  (requirement (id ?r) (applicable TRUE))
  (threshold (value ?t))
  (judgement (req ?r) (verdict supports) (verified TRUE) (confidence ?c1&:(>= ?c1 ?t)))
  (judgement (req ?r) (verdict contradicts) (verified TRUE) (confidence ?c2&:(>= ?c2 ?t)))
  =>
  (assert (status (req ?r) (value disputed) (review TRUE) (reason evidence))))

;; Расхождение правила и модели по содержательному требованию: части записки, где ждём
;; свидетельство, нет, а модель нашла подтверждение в другом месте. Показываем оба результата.
(defrule disputed-structure
  (requirement (id ?r) (applicable TRUE) (part-present FALSE))
  (threshold (value ?t))
  (judgement (req ?r) (verdict supports) (verified TRUE) (confidence ?c&:(>= ?c ?t)))
  =>
  (assert (status (req ?r) (value disputed) (review TRUE) (reason structure))))

;; Модель высказалась, но неуверенно.
(defrule uncertain
  (requirement (id ?r) (applicable TRUE))
  (threshold (value ?t))
  (judgement (req ?r) (verdict supports|contradicts) (verified TRUE) (confidence ?c&:(< ?c ?t)))
  (not (judgement (req ?r) (verdict supports|contradicts) (verified TRUE) (confidence ?c2&:(>= ?c2 ?t))))
  =>
  (assert (status (req ?r) (value not_established) (review TRUE) (reason low-confidence))))

;; Ни одного проверенного свидетельства.
(defrule nothing-found
  (requirement (id ?r) (applicable TRUE))
  (not (judgement (req ?r) (verdict supports|contradicts) (verified TRUE)))
  =>
  (assert (status (req ?r) (value not_established) (review TRUE) (reason no-evidence))))
