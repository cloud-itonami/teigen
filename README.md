# teigen

**提言（teigen）— 事業者自身の声で、命令等・運用に残る書面・収入印紙・出頭の要件の見直しを
求める governed actor。R0（dry-run まで。live 提出は人間の署名を経る）。**

この repo の名前は機能を示さないので先に名乗る: **`kotoba-lang/shomen` が `:ask-ministry` /
`:ask-administration` / `:ask-legislature` と分類した手続きについて、根拠条文を引いた提言を
決定論的に起草し、独立した governor（G1〜G10）を通して、意見公募（行政手続法 §39）・
規制改革・行政手続ホットライン・所管機関への運用要望に**事業者自身の名で**提出する actor**
である。

```bash
nbb --classpath src:test:../../kotoba-lang/shomen/src run_tests.kotoba   # 10 tests / 42 assertions
```

## moushibumi との境界（ここが一番大事）

`cloud-itonami/moushibumi`（申文）は**市民本人**の声 —— 本人が同意し、本人が提出し、
G9 で非党派・非商業、paid-lobbying を禁じる。teigen は**事業者自身**の声で、対象は
その事業者が実際に負っている規制負担（印紙を買って貼って郵送している手続き）に限る。

| | moushibumi | teigen |
|---|---|---|
| 提出者 | 市民本人 | actor を運用する事業者（G1: principal = operator） |
| 入力 | 本人の関心・地域 | shomen の `:ask-*` 分類（`:electronic-now` は出さない） |
| 経路 | 請願・陳情・パブコメ・選挙情報 | パブコメ・規制改革ホットライン・運用要望。**請願は出さない** |
| 本文 | 本人の意見（起草補助） | entry の事実から決定論的に組む。LLM 無し |

どちらも「他人の代わりに政府へ意見を出す」ことはしない。違うのは誰の声かだけ。

## 動き方: `state + event → next-state + effects`

```clojure
(require '[teigen.actor :as a] '[shomen.catalog :as cat])

(def principal {:org "GFTD 株式会社" :operator "GFTD 株式会社"
                :consent {:signed-by "did:key:…" :scope :teigen/submit}})

(a/step (a/init principal)
        [:gap/observed {:entry (assoc (cat/entry :jp-immigration-residence-permission-fee)
                                      :req/confidence :maintainer-verified)
                        :channel :regulatory-reform-hotline :mode :dry-run
                        :ctx {:today "2026-08-23"}}])
;; => [state [[:ledger/append {...}] [:submit/dry-run {:draft {...}}]]]
```

effect は記述であって実行ではない。送信・永続化・署名 UI は host が持つ。

| effect | いつ |
|---|---|
| `[:submit/dry-run …]` | governor `:allow`、mode `:dry-run` |
| `[:human/sign-off …]` | governor `:hold` — その事業者がその経路を使う**初回**の live |
| `[:submit/live …]`    | governor `:allow` で mode `:live`、または hold に人が署名した後 |
| `[:refuse …]`         | governor `:deny`、または shomen が `:unverified` |

## 起草は決定論的で、根拠は entry からしか来ない

`teigen.compose/draft` は LLM を使わない。本文に書けるのは shomen の entry が持つ事実
（手続き名・残る analog 制約とその根拠条文・電子の道の状態・求める措置）だけで、根拠は
全部 `:citations` に列挙される。governor G6 は「citations が本文にあるか」と「citations が
entry の根拠と一致するか」を両方見るので、**起草側が条文を創作すると提出は止まる**（テストで
壊す方向を確認済み）。

## governor

| gate | 守るもの | 落ちる実例（テスト） |
|---|---|---|
| G1 | 提出者 = 運用事業者 | `:org "他社"` |
| G2 | DID 署名付き同意 | consent 無し |
| G3 | live は verified entry から | seed entry で `:live` |
| G4 | `:ask-*` だけ | 会社設立登記（`:electronic-now`） |
| G5 | 経路が開いている・合法 | 案件の無い意見公募 / 請願 |
| G6 | 根拠が entry と一致 | 存在しない法 §99 を本文に足す |
| G7 | 非党派 | 党名 + 支持 |
| G8 | 本文に PII 無し | メールアドレス / 電話番号 |
| G9 | 二重提出しない | 同じ entry × 経路 × 案件 |
| G10 | 初回経路は人間 | 初めてのホットライン live → `:hold` |

## 経路

`teigen.channels/registry`。法定の手続き（意見公募）と運用上の窓口（ホットライン）を
混ぜない —— 前者は案件と期間が無ければ存在せず、後者は随時開いている。請願は
`:handled-by :moushibumi` で、teigen は提出しない。

## R0 で無いもの

- live 提出の host（ブラウザ自動化・フォーム送信）。`[:submit/live …]` を受ける側は未実装
- 台帳の永続化（`:ledger` は state の vector。kotobase への append は host）
- 意見公募案件の自動検出（`:comment-window` は host が与える）
- LLM による言い回しの調整（入れるときは本文を書き換えさせず citations を動かさない）

## License

AGPL-3.0-or-later
