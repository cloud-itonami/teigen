(ns teigen.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.text :as str]
            [shomen.catalog :as cat]
            [teigen.actor :as a]
            [teigen.channels :as ch]
            [teigen.compose :as compose]
            [teigen.governor :as gov]))

(def principal
  {:org "GFTD 株式会社" :operator "GFTD 株式会社"
   :consent {:signed-by "did:key:z6MkTest" :scope :teigen/submit}})

(def immigration (cat/entry :jp-immigration-residence-permission-fee))
(def verified-immigration (assoc immigration :req/confidence :maintainer-verified))
(def ctx {:today "2026-08-23"})

(deftest compose-cites-only-entry-facts
  (let [d (compose/draft immigration principal)]
    (is (= :ministry (:addressee d)))
    (is (= [:revenue-stamp] (mapv :a/kind (shomen.classify/remaining-analog immigration))))
    (is (every? #(str/includes? (:body d) %) (:citations d)) "引いた条文は全部本文にある")
    (is (str/includes? (:body d) "提出者: GFTD 株式会社")))
  (testing "求めるものが無い entry は nil、調べていない entry は拒否"
    (is (nil? (compose/draft (cat/entry :jp-commercial-registration-incorporation) principal)))
    (is (= :unverified (:refused (compose/draft (cat/entry :jp-civil-suit-filing) principal))))))

(deftest channels-open
  (is (true? (ch/open? :regulatory-reform-hotline ctx)))
  (is (false? (ch/open? :public-comment ctx)) "案件も期間も無い意見公募は存在しない")
  (is (true? (ch/open? :public-comment (assoc ctx :comment-window {:case-id "300080001" :opens "2026-08-01" :closes "2026-08-31"}))))
  (is (false? (ch/open? :public-comment (assoc ctx :comment-window {:case-id "x" :opens "2026-07-01" :closes "2026-07-31"}))) "期間が過ぎた")
  (is (= #{:public-comment :regulatory-reform-hotline} (ch/for-target :ministry)))
  (is (not (contains? (ch/for-target :diet) :petition)) "請願は teigen から出さない"))

(def default-ctx ctx)

(defn- decide [& {:keys [entry channel mode c ledger principal draft]
                  :or {entry verified-immigration channel :regulatory-reform-hotline
                       mode :dry-run c default-ctx ledger []}}]
  (let [pr (or principal teigen.actor-test/principal)]
    (gov/decide {:entry entry :draft (or draft (compose/draft entry pr))
                 :principal pr :channel channel :mode mode :ctx c :ledger ledger})))

(deftest governor-allows-a-clean-dry-run
  (is (= :allow (:decision (decide)))))

(deftest governor-denies-each-gate-for-its-own-reason
  (testing "G1 第三者のための提出"
    (is (= [:G1] (mapv :gate (:reasons (decide :principal (assoc principal :org "他社")))))))
  (testing "G2 同意が無い"
    (is (some #(= :G2 (:gate %)) (:reasons (decide :principal (dissoc principal :consent))))))
  (testing "G3 seed から live は出せない"
    (is (= [:G3] (mapv :gate (:reasons (decide :entry immigration :mode :live))))))
  (testing "G4 求めるものが無い route"
    (let [e (assoc (cat/entry :jp-commercial-registration-incorporation) :req/confidence :maintainer-verified)
          d (gov/decide {:entry e :draft {:body "x" :citations ["商業登記法 §17 ①（申請書を提出）"] :addressee :ministry}
                         :principal principal :channel :regulatory-reform-hotline :mode :dry-run :ctx ctx :ledger []})]
      (is (some #(= :G4 (:gate %)) (:reasons d)))))
  (testing "G5 閉じた意見公募"
    (is (= [:G5] (mapv :gate (:reasons (decide :channel :public-comment))))))
  (testing "G5 請願は出さない"
    (is (some #(and (= :G5 (:gate %)) (str/includes? (:detail %) "moushibumi")) (:reasons (decide :channel :petition))))))

(deftest governor-citations-must-match-entry
  (let [d (compose/draft verified-immigration principal)
        forged (update d :citations conj "存在しない法 §99")
        forged-body (assoc forged :body (str (:body d) "\n存在しない法 §99"))]
    (is (some #(= :G6 (:gate %)) (:reasons (decide :draft forged))) "本文に無い条文")
    (is (some #(and (= :G6 (:gate %)) (str/includes? (:detail %) "創作")) (:reasons (decide :draft forged-body)))
        "entry に無い条文を本文にも citations にも入れた = 創作")))

(deftest governor-non-partisan-and-no-pii
  (let [d (compose/draft verified-immigration principal)]
    (is (some #(= :G7 (:gate %)) (:reasons (decide :draft (update d :body str "\n自民党を支持します")))))
    (is (some #(= :G8 (:gate %)) (:reasons (decide :draft (update d :body str "\n連絡先 jun@example.com")))))
    (is (some #(= :G8 (:gate %)) (:reasons (decide :draft (update d :body str "\n03-1234-5678")))))))

(deftest governor-dedupe-and-first-time-hold
  (let [submitted {:event :submitted :entry-id :jp-immigration-residence-permission-fee
                   :channel :regulatory-reform-hotline :case-id nil :principal "GFTD 株式会社" :mode :live}]
    (is (some #(= :G9 (:gate %)) (:reasons (decide :ledger [submitted]))))
    (is (= :hold (:decision (decide :mode :live))) "初回の live は人間の署名待ち")
    (is (= :allow (:decision (decide :mode :live
                                     :ledger [(assoc submitted :entry-id :other)])))
        "同じ経路を一度 live で通していれば allow")))

(deftest step-dry-run-then-hold-then-sign
  (let [s0 (a/init principal)
        [s1 fx1] (a/step s0 [:gap/observed {:entry verified-immigration :channel :regulatory-reform-hotline :mode :dry-run :ctx ctx}])]
    (is (= [:ledger/append :submit/dry-run] (mapv first fx1)))
    (is (= :dry-run (:event (last (:ledger s1)))))
    (let [[s2 fx2] (a/step s1 [:gap/observed {:entry verified-immigration :channel :regulatory-reform-hotline :mode :live :ctx ctx}])]
      (is (= [:ledger/append :human/sign-off] (mapv first fx2)))
      (is (= 1 (count (:held s2))))
      (let [sid (first (keys (:held s2)))
            [s3 fx3] (a/step s2 [:human/signed {:submission-id sid :signed-by "did:key:z6MkOwner"}])]
        (is (= [:ledger/append :submit/live] (mapv first fx3)))
        (is (empty? (:held s3)))
        (is (= :submitted (:event (last (:ledger s3)))))
        (is (= [0 1 2] (mapv :seq (:ledger s3))) "台帳は順序を持つ")
        (testing "同じものをもう一度出そうとすると G9"
          (let [[_ fx4] (a/step s3 [:gap/observed {:entry verified-immigration :channel :regulatory-reform-hotline :mode :live :ctx ctx}])]
            (is (= :refuse (first (second fx4))))
            (is (some #(= :G9 (:gate %)) (:reasons (second (second fx4)))))))))))

(deftest step-refuses-seed-live-and-unverified
  (let [s0 (a/init principal)
        [_ fx] (a/step s0 [:gap/observed {:entry immigration :channel :regulatory-reform-hotline :mode :live :ctx ctx}])]
    (is (= :refuse (first (second fx))))
    (is (some #(= :G3 (:gate %)) (:reasons (second (second fx))))))
  (let [[_ fx] (a/step (a/init principal) [:gap/observed {:entry (cat/entry :jp-civil-suit-filing) :channel :regulatory-reform-hotline :mode :dry-run :ctx ctx}])]
    (is (= :refuse (first (second fx))))
    (is (= :unverified (:reason (second (second fx)))))))

(deftest step-signature-without-hold-is-refused
  (let [[_ fx] (a/step (a/init principal) [:human/signed {:submission-id "nope" :signed-by "x"}])]
    (is (= :refuse (first (second fx))))))
