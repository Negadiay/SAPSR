;;; Продукционные правила над утверждениями (план, З4, шаг 4).
;;;
;;; Факты загружает sapsr/content/reasoning.py:
;;;   attribute — атрибут из словаря понятий (kb/concepts.yaml);
;;;   claim     — утверждение модели, прошедшее проверку цитаты и нормализацию значений.
;;; Правило выводит факт firing; по нему и по каталогу (kb/rules/50-content.yaml)
;;; строится замечание с цитатами из обоих мест.

(deftemplate attribute
  (slot name (type STRING))
  (slot cardinality (type SYMBOL) (allowed-symbols single multi text))
  ; части записки, где заявленное должно быть описано (must_be_described_in)
  (multislot described-in (type STRING)))

(deftemplate claim
  (slot id (type STRING))
  (slot attr (type STRING))
  ; канонические значения, отсортированные — множества сравниваются как строки
  (multislot vals (type STRING))
  (slot exhaustive (type SYMBOL) (allowed-symbols TRUE FALSE))
  (slot section (type STRING))
  (slot kind (type STRING)))

(deftemplate firing
  (slot rule (type SYMBOL))
  (slot attr (type STRING))
  (multislot claims (type STRING)))

;; Однозначный атрибут описан в разных разделах разными значениями.
;; Пример из записки: «онтология» во введении и «конфигурационный файл» в реализации.
(defrule conflict_single
  (attribute (name ?a) (cardinality single))
  (claim (id ?c1) (attr ?a) (vals $?v1) (section ?s1))
  (claim (id ?c2) (attr ?a) (vals $?v2) (section ?s2))
  (test (< (str-compare ?c1 ?c2) 0))
  (test (neq ?s1 ?s2))
  (test (neq (implode$ $?v1) (implode$ $?v2)))
  =>
  (assert (firing (rule conflict_single) (attr ?a) (claims ?c1 ?c2))))

;; Многозначный атрибут: в двух разделах названы разные полные перечни.
;; Пример из записки: «DOCX и PDF» во введении, «только PDF» в требованиях.
(defrule conflict_multi_exhaustive
  (attribute (name ?a) (cardinality multi))
  (claim (id ?c1) (attr ?a) (vals $?v1) (exhaustive TRUE) (section ?s1))
  (claim (id ?c2) (attr ?a) (vals $?v2) (exhaustive TRUE) (section ?s2))
  (test (< (str-compare ?c1 ?c2) 0))
  (test (neq ?s1 ?s2))
  (test (neq (implode$ $?v1) (implode$ $?v2)))
  =>
  (assert (firing (rule conflict_multi) (attr ?a) (claims ?c1 ?c2))))

;; Многозначный атрибут: в одном разделе перечень назван полным, а в другом
;; упомянуто значение, которого в этом перечне нет.
(defrule conflict_multi_outside
  (attribute (name ?a) (cardinality multi))
  (claim (id ?c1) (attr ?a) (vals $?v1) (exhaustive TRUE) (section ?s1))
  (claim (id ?c2) (attr ?a) (vals $? ?x $?) (exhaustive FALSE) (section ?s2))
  (test (neq ?s1 ?s2))
  (test (not (member$ ?x $?v1)))
  =>
  (assert (firing (rule conflict_multi) (attr ?a) (claims ?c1 ?c2))))

;; Заявлено (например, во введении), но в частях, где это обязано быть описано
;; (проектирование, реализация), нет ни одного утверждения об этом атрибуте.
;; Пример из записки: нейросетевой компонент только упомянут.
(defrule claim_without_support
  (attribute (name ?a) (described-in $?parts))
  (test (> (length$ $?parts) 0))
  (claim (id ?c) (attr ?a) (kind ?k))
  (test (not (member$ ?k $?parts)))
  (not (claim (attr ?a) (kind ?k2&:(member$ ?k2 $?parts))))
  =>
  (assert (firing (rule claim_without_support) (attr ?a) (claims ?c))))
