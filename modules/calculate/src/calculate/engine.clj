(ns calculate.engine
    "Mathematical expression evaluation engine with 100+ pre-loaded functions."
    (:refer-clojure :exclude [format])
    (:require [sci.core :as sci]
              [clojure.string :as str]))

;; Math function definitions - 50+ pre-loaded functions
(def math-fns
     {;; Core arithmetic
      '+ +, '- -, '* *, '/ /
      'mod mod, 'quot quot, 'rem rem
      'inc inc, 'dec dec

   ;; Powers and roots
      'pow #(Math/pow (double %1) (double %2))
      'sqrt #(Math/sqrt (double %))
      'cbrt #(Math/cbrt (double %))
      'exp #(Math/exp (double %))

   ;; Logarithms
      'ln #(Math/log (double %))
      'log10 #(Math/log10 (double %))
      'log2 #(/ (Math/log (double %)) (Math/log 2.0))
      'logb #(/ (Math/log (double %1)) (Math/log (double %2)))

   ;; Trigonometry (radians)
      'sin #(Math/sin (double %))
      'cos #(Math/cos (double %))
      'tan #(Math/tan (double %))
      'asin #(Math/asin (double %))
      'acos #(Math/acos (double %))
      'atan #(Math/atan (double %))
      'atan2 #(Math/atan2 (double %1) (double %2))
      'sinh #(Math/sinh (double %))
      'cosh #(Math/cosh (double %))
      'tanh #(Math/tanh (double %))

   ;; Trigonometry (degrees) - convenience functions
      'sind #(Math/sin (Math/toRadians (double %)))
      'cosd #(Math/cos (Math/toRadians (double %)))
      'tand #(Math/tan (Math/toRadians (double %)))

   ;; Rounding and magnitude
      'abs #(Math/abs (double %))
      'floor #(Math/floor (double %))
      'ceil #(Math/ceil (double %))
      'round #(Math/round (double %))
      'sign #(Math/signum (double %))
      'trunc #(long (Math/floor (double %)))

   ;; Mathematical Constants
      'pi Math/PI
      'e Math/E
      'tau (* 2.0 Math/PI)
      'phi (/ (+ 1.0 (Math/sqrt 5.0)) 2.0)  ; golden ratio

   ;; Crypto/Blockchain Decimals
      'eth-decimals 18
      'btc-decimals 8
      'hash-decimals 9
      'usdc-decimals 6
      'usdt-decimals 6

   ;; Crypto Unit Conversions
      'wei-per-eth 1000000000000000000N
      'gwei-per-eth 1000000000N
      'sat-per-btc 100000000N

   ;; Time/Date Constants
      'year-seconds 31536000
      'day-seconds 86400
      'hour-seconds 3600
      'minute-seconds 60
      'week-seconds 604800
      'year-days 365
      'leap-year-days 366

   ;; Blockchain Block Times (seconds)
      'eth-block-time-seconds 12
      'btc-block-time-seconds 600
      'blocks-per-day-eth 7200
      'blocks-per-day-btc 144

   ;; Finance/Time Periods
      'months-per-year 12
      'weeks-per-year 52
      'quarters-per-year 4
      'days-per-week 7

   ;; DeFi Common Values
      'typical-slippage 0.005  ; 0.5%
      'high-slippage 0.01      ; 1%

   ;; Comparisons
      '< <, '> >, '<= <=, '>= >=, '= =, 'not= not=

   ;; Vector/sequence operations
      'sum #(reduce + %)
      'product #(reduce * %)
      'vmin #(apply min %)
      'vmax #(apply max %)
      'count count

   ;; Statistics
      'mean (fn [xs]
              (/ (reduce + xs) (count xs)))

      'median (fn [xs]
                (let [sorted (sort xs)
                      n (count sorted)
                      mid (quot n 2)]
                  (if (odd? n)
                    (nth sorted mid)
                    (/ (+ (nth sorted mid)
                          (nth sorted (dec mid)))
                       2))))

      'variance (fn [xs]
                  (let [m (/ (reduce + xs) (count xs))
                        n (count xs)]
                    (/ (reduce + (map #(* (- % m) (- % m)) xs))
                       n)))

      'stdev (fn [xs]
               (Math/sqrt ((fn [xs]
                             (let [m (/ (reduce + xs) (count xs))
                                   n (count xs)]
                               (/ (reduce + (map #(* (- % m) (- % m)) xs))
                                  n))) xs)))

   ;; Linear algebra basics
      'dot (fn [v1 v2]
             (reduce + (map * v1 v2)))

      'norm (fn [v]
              (Math/sqrt (reduce + (map #(* % %) v))))

      'cross (fn [[a1 a2 a3] [b1 b2 b3]]
               [(- (* a2 b3) (* a3 b2))
                (- (* a3 b1) (* a1 b3))
                (- (* a1 b2) (* a2 b1))])

   ;; Number Formatting Utilities
      'with-commas (fn [num]
                     (let [s (str num)
                           dot-idx (or (first (keep-indexed #(when (= %2 \.) %1) s)) (count s))
                           whole (subs s 0 dot-idx)
                           decimal (when (< dot-idx (count s)) (subs s dot-idx))
                           rev-whole (vec (reverse whole))
                           grouped (partition-all 3 rev-whole)
                           rev-grouped (map reverse grouped)
                           formatted (apply str (reverse (interpose "," (map #(apply str %) rev-grouped))))]
                       (str formatted (or decimal ""))))

      'round-to (fn [num decimals]
                  (let [factor (Math/pow 10 decimals)]
                    (/ (Math/round (* (double num) factor)) factor)))

      'scientific (fn [num]
                    (clojure.core/format "%.2e" (double num)))

      'to-decimal (fn [num]
                    (double num))

   ;; Financial & Percentage Functions (return rich maps)
      'percent-change (fn [old new]
                        (let [change (- new old)
                              percent (* (/ change old) 100.0)
                              direction (cond
                                          (pos? change) :increase
                                          (neg? change) :decrease
                                          :else :unchanged)
                              formatted (str (if (pos? change) "+" "") percent "%")]
                          {:change change
                           :percent percent
                           :direction direction
                           :formatted formatted
                           :old-value old
                           :new-value new}))

      'percent-of (fn [part total]
                    (let [percentage (* (/ part total) 100.0)
                          decimal (/ part total)]
                      {:percentage percentage
                       :decimal decimal
                       :formatted (str percentage "%")
                       :part part
                       :total total}))

      'percentage (fn [total percent]
                    (let [value (* total (/ percent 100.0))]
                      {:value value
                       :of-total total
                       :percent percent
                       :formatted (str value " (" percent "% of " total ")")}))

      'roi (fn [initial final]
             (let [profit (- final initial)
                   percent (* (/ profit initial) 100.0)
                   multiplier (/ final initial)]
               {:profit profit
                :roi-percent percent
                :multiplier multiplier
                :formatted (str (if (pos? profit) "+" "") percent "%")
                :initial initial
                :final final}))

      'compound-interest (fn [principal rate periods]
                           (let [final (* principal (Math/pow (+ 1 rate) periods))
                                 total-interest (- final principal)]
                             {:initial principal
                              :final final
                              :total-interest total-interest
                              :rate rate
                              :periods periods
                              :formatted (str "$" final " (+" total-interest " interest)")}))

      'simple-interest (fn [principal rate time]
                         (let [interest (* principal rate time)
                               final (+ principal interest)]
                           {:initial principal
                            :final final
                            :interest interest
                            :rate rate
                            :time time
                            :formatted (str "$" final " (+" interest " interest)")}))

      'market-share (fn [my-amount total]
                      (let [percentage (* (/ my-amount total) 100.0)
                            decimal (/ my-amount total)
                            ratio (str "1:" (long (/ total my-amount)))]
                        {:percentage percentage
                         :decimal decimal
                         :ratio ratio
                         :formatted (str percentage "%")
                         :my-amount my-amount
                         :total total}))

      'token-value (fn [price holdings]
                     (let [total-value (* price holdings)]
                       {:total-value total-value
                        :price price
                        :holdings holdings
                        :formatted (str "$" total-value)
                        :per-token (str "$" price)}))

   ;; Date/Time/Duration Functions (using java.time, all UTC)
      'unix-now (fn []
                  (let [now (java.time.Instant/now)
                        unix (.getEpochSecond now)
                        dt (java.time.ZonedDateTime/ofInstant now java.time.ZoneOffset/UTC)]
                    {:unix unix
                     :iso (str (.toLocalDate dt))
                     :date (str (.toLocalDate dt))
                     :time (str (.toLocalTime dt))
                     :formatted (str dt)}))

      'unix-to-date (fn [timestamp]
                      (let [instant (java.time.Instant/ofEpochSecond (long timestamp))
                            dt (java.time.ZonedDateTime/ofInstant instant java.time.ZoneOffset/UTC)
                            ld (.toLocalDate dt)]
                        {:unix timestamp
                         :date (str ld)
                         :iso (str ld)
                         :year (.getYear ld)
                         :month (.getMonthValue ld)
                         :day (.getDayOfMonth ld)}))

      'date-to-unix (fn [date-str]
                      (let [ld (java.time.LocalDate/parse date-str)
                            zdt (java.time.ZonedDateTime/of ld (java.time.LocalTime/of 0 0) java.time.ZoneOffset/UTC)
                            instant (.toInstant zdt)
                            unix (.getEpochSecond instant)]
                        {:date date-str
                         :unix unix
                         :iso (str ld)}))

      'days-between (fn [start end]
                      (let [start-ld (if (number? start)
                                       (.toLocalDate (java.time.ZonedDateTime/ofInstant
                                                      (java.time.Instant/ofEpochSecond (long start))
                                                      java.time.ZoneOffset/UTC))
                                       (java.time.LocalDate/parse (str start)))
                            end-ld (if (number? end)
                                     (.toLocalDate (java.time.ZonedDateTime/ofInstant
                                                    (java.time.Instant/ofEpochSecond (long end))
                                                    java.time.ZoneOffset/UTC))
                                     (java.time.LocalDate/parse (str end)))
                            days (.between java.time.temporal.ChronoUnit/DAYS start-ld end-ld)]
                        {:start (str start-ld)
                         :end (str end-ld)
                         :days days
                         :weeks (/ days 7)
                         :hours (* days 24)}))

      'add-days (fn [date days-to-add]
                  (let [ld (if (number? date)
                             (.toLocalDate (java.time.ZonedDateTime/ofInstant
                                            (java.time.Instant/ofEpochSecond (long date))
                                            java.time.ZoneOffset/UTC))
                             (java.time.LocalDate/parse (str date)))
                        new-ld (.plusDays ld days-to-add)
                        zdt (java.time.ZonedDateTime/of new-ld (java.time.LocalTime/of 0 0) java.time.ZoneOffset/UTC)
                        new-unix (.getEpochSecond (.toInstant zdt))]
                    {:original (str ld)
                     :added-days days-to-add
                     :result (str new-ld)
                     :unix new-unix}))

      'add-seconds (fn [timestamp seconds-to-add]
                     (let [instant (java.time.Instant/ofEpochSecond (long timestamp))
                           new-instant (.plusSeconds instant seconds-to-add)
                           new-unix (.getEpochSecond new-instant)
                           dt (java.time.ZonedDateTime/ofInstant new-instant java.time.ZoneOffset/UTC)]
                       {:original timestamp
                        :added-seconds seconds-to-add
                        :result new-unix
                        :date (str (.toLocalDate dt))}))

      'days-until (fn [date]
                    (let [target-ld (if (number? date)
                                      (.toLocalDate (java.time.ZonedDateTime/ofInstant
                                                     (java.time.Instant/ofEpochSecond (long date))
                                                     java.time.ZoneOffset/UTC))
                                      (java.time.LocalDate/parse (str date)))
                          now-ld (java.time.LocalDate/now java.time.ZoneOffset/UTC)
                          days (.between java.time.temporal.ChronoUnit/DAYS now-ld target-ld)]
                      {:target-date (str target-ld)
                       :days-until days
                       :weeks-until (/ days 7)
                       :in-past (neg? days)}))

      'timestamp-in-days (fn [days]
                           (let [now (java.time.Instant/now)
                                 target (.plusSeconds now (* days 86400))
                                 unix (.getEpochSecond target)
                                 dt (java.time.ZonedDateTime/ofInstant target java.time.ZoneOffset/UTC)]
                             {:days-from-now days
                              :target-date (str (.toLocalDate dt))
                              :unix unix}))

      'lock-period-end (fn [start-unix duration-days]
                         (let [start-instant (java.time.Instant/ofEpochSecond (long start-unix))
                               end-instant (.plusSeconds start-instant (* duration-days 86400))
                               end-unix (.getEpochSecond end-instant)
                               start-dt (java.time.ZonedDateTime/ofInstant start-instant java.time.ZoneOffset/UTC)
                               end-dt (java.time.ZonedDateTime/ofInstant end-instant java.time.ZoneOffset/UTC)
                               now (java.time.Instant/now)
                               days-remaining (long (/ (- end-unix (.getEpochSecond now)) 86400))]
                           {:locked-at (str (.toLocalDate start-dt))
                            :duration-days duration-days
                            :unlock-date (str (.toLocalDate end-dt))
                            :unlock-unix end-unix
                            :days-remaining days-remaining
                            :unlocked (neg? days-remaining)}))

      'is-unlocked (fn [lock-end-timestamp]
                     (let [end-instant (java.time.Instant/ofEpochSecond (long lock-end-timestamp))
                           now (java.time.Instant/now)
                           unlocked (.isAfter now end-instant)
                           dt (java.time.ZonedDateTime/ofInstant end-instant java.time.ZoneOffset/UTC)
                           days-remaining (long (/ (- lock-end-timestamp (.getEpochSecond now)) 86400))]
                       {:unlock-date (str (.toLocalDate dt))
                        :unlocked unlocked
                        :days-remaining (if unlocked 0 days-remaining)}))

   ;; Blockchain/Crypto Unit Conversions (High Precision)
      'to-smallest-unit (fn [amount decimals]
                          (let [factor (Math/pow 10 decimals)
                                smallest-unit (long (* amount factor))]
                            {:amount amount
                             :smallest-unit smallest-unit
                             :decimals decimals
                             :formatted (str smallest-unit)}))

      'from-smallest-unit (fn [units decimals]
                            (let [factor (Math/pow 10 decimals)
                                  amount (/ units factor)]
                              {:smallest-unit units
                               :amount amount
                               :decimals decimals
                               :formatted (str amount)}))

      'wei->ether (fn [wei]
                    (let [ether (/ wei 1000000000000000000.0)
                          gwei (/ wei 1000000000.0)]
                      {:wei wei
                       :ether ether
                       :gwei gwei
                       :formatted (str ether " ETH")}))

      'ether->wei (fn [eth]
                    (let [wei (long (* eth 1000000000000000000))
                          gwei (long (* eth 1000000000))]
                      {:ether eth
                       :wei wei
                       :gwei gwei
                       :formatted (str wei " wei")}))

      'sats->btc (fn [sats]
                   (let [btc (/ sats 100000000.0)]
                     {:satoshis sats
                      :btc btc
                      :formatted (str btc " BTC")}))

      'btc->sats (fn [btc]
                   (let [sats (long (* btc 100000000))]
                     {:btc btc
                      :satoshis sats
                      :formatted (str sats " sats")}))

   ;; Market & Portfolio Calculations
      'market-cap (fn [price circulation]
                    (let [mcap (* price circulation)
                          billions (/ mcap 1000000000.0)]
                      {:price price
                       :circulation circulation
                       :market-cap mcap
                       :billions billions
                       :formatted (str "$" mcap)}))

   ;; DeFi Calculations
      'impermanent-loss (fn [initial-price current-price]
                          (let [price-ratio (/ current-price initial-price)
                                il-multiplier (/ (* 2 (Math/sqrt price-ratio)) (+ 1 price-ratio))
                                il-percent (* (- 1 il-multiplier) 100.0)
                                hodl-value (* 0.5 (+ initial-price current-price))
                                pool-value (* initial-price il-multiplier)
                                vs-hodl-percent (* (/ (- pool-value hodl-value) hodl-value) 100.0)]
                            {:initial-price initial-price
                             :current-price current-price
                             :price-change-percent (* (- price-ratio 1) 100.0)
                             :impermanent-loss-percent il-percent
                             :vs-hodl-percent vs-hodl-percent
                             :formatted (str il-percent "% IL")}))

      'liquidity-pool-share (fn [token-a-amount token-b-amount pool-token-a pool-token-b]
                              (let [pool-share-percent (* (/ token-a-amount pool-token-a) 100.0)]
                                {:your-token-a token-a-amount
                                 :your-token-b token-b-amount
                                 :pool-token-a pool-token-a
                                 :pool-token-b pool-token-b
                                 :pool-share-percent pool-share-percent
                                 :formatted (str pool-share-percent "% of pool")}))

      'apy-to-apr (fn [apy compounds-per-year]
                    (let [apr (* (- (Math/pow (+ 1 (/ apy 100.0)) (/ 1.0 compounds-per-year)) 1) compounds-per-year 100.0)
                          daily-rate (/ apr 365.0)]
                      {:apy apy
                       :apr apr
                       :compounds compounds-per-year
                       :daily-rate daily-rate}))

      'apr-to-apy (fn [apr compounds-per-year]
                    (let [apy (* (- (Math/pow (+ 1 (/ apr compounds-per-year 100.0)) compounds-per-year) 1) 100.0)
                          daily-rate (/ apr 365.0)]
                      {:apr apr
                       :apy apy
                       :compounds compounds-per-year
                       :daily-rate daily-rate}))

      'staking-rewards (fn [amount apy duration-days]
                         (let [daily-rate (/ apy 365.0 100.0)
                               rewards (* amount daily-rate duration-days)
                               total (+ amount rewards)
                               daily-rewards (/ rewards duration-days)]
                           {:principal amount
                            :apy apy
                            :days duration-days
                            :rewards rewards
                            :total total
                            :daily-rewards daily-rewards
                            :formatted (str "$" rewards " rewards")}))

      'slippage-impact (fn [amount-in reserve-in reserve-out]
                         (let [amount-in-with-fee (* amount-in 0.997)  ; 0.3% fee
                               amount-out (/ (* amount-in-with-fee reserve-out)
                                             (+ reserve-in amount-in-with-fee))
                               price-before (/ reserve-out reserve-in)
                               price-after (/ (- reserve-out amount-out) (+ reserve-in amount-in))
                               price-impact-percent (* (/ (- price-after price-before) price-before) 100.0)]
                           {:amount-in amount-in
                            :reserve-in reserve-in
                            :reserve-out reserve-out
                            :amount-out amount-out
                            :price-impact-percent (Math/abs price-impact-percent)
                            :slippage (Math/abs price-impact-percent)
                            :effective-price (/ amount-in amount-out)
                            :formatted (str (Math/abs price-impact-percent) "% slippage")}))

   ;; Leverage & Liquidation
      'liquidation-price (fn [collateral-value borrowed-value liquidation-threshold]
                           (let [liq-price (* borrowed-value (/ 1.0 liquidation-threshold))
                                 health-factor (/ (* collateral-value liquidation-threshold) borrowed-value)
                                 safe (>= health-factor 1.0)]
                             {:collateral collateral-value
                              :borrowed borrowed-value
                              :threshold liquidation-threshold
                              :liquidation-price liq-price
                              :health-factor health-factor
                              :safe safe
                              :formatted (str "$" liq-price " liquidation")}))

      'leverage-ratio (fn [collateral borrowed]
                        (let [equity (- collateral borrowed)
                              leverage (/ collateral equity)
                              ltv (/ borrowed collateral)]
                          {:collateral collateral
                           :borrowed borrowed
                           :leverage leverage
                           :equity equity
                           :ltv ltv
                           :formatted (str leverage "x leverage")}))

   ;; Gas & Fee Calculations
      'gas-cost (fn [gas-used gwei-price eth-price]
                  (let [gas-cost-gwei (* gas-used gwei-price)
                        gas-cost-eth (/ gas-cost-gwei 1000000000.0)
                        gas-cost-usd (* gas-cost-eth eth-price)]
                    {:gas-used gas-used
                     :gwei-price gwei-price
                     :gas-cost-eth gas-cost-eth
                     :gas-cost-usd gas-cost-usd
                     :eth-price eth-price
                     :formatted (str "$" gas-cost-usd)}))})

;;=============================================================================
;; Phase 3B: Type-Safe Token Conversion System
;;=============================================================================

;; Unit Normalization

(defn normalize-unit
  "Convert string or keyword to lowercase keyword for consistent unit handling.
   Accepts both for flexibility:
   - Keywords: easier typing, no escaping (e.g., :usd, :USD, :hash)
   - Strings: compatibility with external data (e.g., \"usd\", \"USD\", \"hash\")
   All normalized to lowercase keywords for comparison.

   Examples:
     (normalize-unit :usd)   => :usd
     (normalize-unit :USD)   => :usd
     (normalize-unit \"usd\") => :usd
     (normalize-unit \"USD\") => :usd"
  [unit]
  (cond
    (keyword? unit) (keyword (.toLowerCase (name unit)))
    (string? unit) (keyword (.toLowerCase unit))
    :else (throw (ex-info "Unit must be string or keyword"
                          {:unit unit
                           :type (type unit)}))))

;; Token Amount Support Functions

(defn token-amount?
  "Check if value is a valid token amount tuple: [amount unit]
   Unit can be keyword or string.

   Examples:
     (token-amount? [1000 :hash])    => true
     (token-amount? [0.032 \"usd\"])  => true
     (token-amount? [1.5 :btc])      => true"
  [x]
  (and (vector? x)
       (= 2 (count x))
       (number? (first x))
       (or (keyword? (second x)) (string? (second x)))))

(defn get-amount
  "Extract amount from token tuple"
  [[amt _]]
  amt)

(defn get-unit
  "Extract unit from token tuple as normalized keyword"
  [[_ unit]]
  (normalize-unit unit))

(defn token-amount
  "Construct a token amount tuple with normalized unit.
   Unit can be provided as keyword or string.

   Examples:
     (token-amount 1000 :hash)    => [1000 :hash]
     (token-amount 0.032 \"usd\")  => [0.032 :usd]"
  [amt unit]
  [amt (normalize-unit unit)])

;; Rate Validation

(defn valid-rate?
  "Validate exchange rate structure and values.
   Returns {:valid true} or {:valid false :error msg}

   Accepts flexible division operators: :/ (keyword), '/ (symbol), or \"/\" (string)

   CRITICAL: Rejects same-unit rates (e.g., [:/ [2 :hash] [1 :hash]])
   as they are semantically nonsensical - use plain multipliers instead."
  [rate]
  (if-not (and (vector? rate)
               (= 3 (count rate)))
    {:valid false :error "Rate must be a 3-element vector"}
    (let [[op num denom] rate]
      (cond
        (not (#{:/ '/ "/"} op))
        {:valid false :error "Must use division operator (:/, '/, or \"/\") for rates"}

        (not (and (token-amount? num) (token-amount? denom)))
        {:valid false :error "Rate numerator and denominator must be token amounts"}

        :else
        (let [num-amt (get-amount num)
              denom-amt (get-amount denom)]
          (cond
            (or (zero? num-amt) (zero? denom-amt))
            {:valid false :error "Rate amounts cannot be zero"}

            (or (neg? num-amt) (neg? denom-amt))
            {:valid false :error "Rate amounts must be positive"}

            (= (get-unit num) (get-unit denom))
            {:valid false :error "Same-unit rates are invalid - use plain numbers for multipliers"}

            :else
            {:valid true}))))))

;; Token Conversion Function

(defn token-convert
  "Convert token amounts using exchange rates.

  Signatures:
    (token-convert [amt from] to-unit rate)  ; Full validation
    (token-convert [amt from] rate)           ; Infer target from rate

  Examples:
    (token-convert [1000 'hash'] 'usd' [/ [0.032 'usd'] [1 'hash']])
    => [32.0 'usd']

    (token-convert [10 'usd'] [/ [0.032 'usd'] [1 'hash']])
    => [312.5 'hash']"

  ;; Two-arity: infer target from rate, preserve rate's unit format
  ([amount-tuple rate]
   (let [[_ [_ num-unit] [_ denom-unit]] rate
         from-unit-norm (get-unit amount-tuple)
         ;; Infer target AND preserve its original format from rate
         target-unit (if (= from-unit-norm (normalize-unit denom-unit))
                       num-unit    ; FROM denom → TO num (preserve num format)
                       denom-unit) ; FROM num → TO denom (preserve denom format)
         to-unit target-unit]
     (token-convert amount-tuple to-unit rate)))

  ;; Three-arity: explicit validation
  ([amount-tuple to-unit rate]
   (when-not (token-amount? amount-tuple)
     (throw (ex-info "First argument must be a token amount tuple [amount unit]"
                     {:provided amount-tuple})))

   ;; Validate rate
   (let [validation (valid-rate? rate)]
     (when-not (:valid validation)
       (throw (ex-info "Invalid rate" validation))))

   ;; Extract and normalize all components for comparison
   (let [[amount from-unit] amount-tuple
         from-unit-norm (normalize-unit from-unit)
         [_ [num-amt num-unit] [denom-amt denom-unit]] rate
         num-unit-norm (normalize-unit num-unit)
         denom-unit-norm (normalize-unit denom-unit)
         to-unit-norm (normalize-unit to-unit)]

     ;; Validate target matches rate (using normalized units)
     (when-not (or (= to-unit-norm num-unit-norm) (= to-unit-norm denom-unit-norm))
       (throw (ex-info "Target unit doesn't match rate units"
                       {:target to-unit-norm
                        :rate-units [num-unit-norm denom-unit-norm]})))

     ;; Perform conversion (normalized for comparison, preserve to-unit format in output)
     (cond
       ;; FROM denominator TO numerator: multiply by (num/denom)
       (and (= from-unit-norm denom-unit-norm) (= to-unit-norm num-unit-norm))
       [(*' amount (/ num-amt denom-amt)) to-unit]  ; Preserve caller's format!

       ;; FROM numerator TO denominator: divide by (num/denom)
       (and (= from-unit-norm num-unit-norm) (= to-unit-norm denom-unit-norm))
       [(*' amount (/ denom-amt num-amt)) to-unit]  ; Preserve caller's format!

       :else
       (throw (ex-info "Units don't match rate"
                       {:from from-unit-norm
                        :to to-unit-norm
                        :rate rate}))))))

;; Rate Utility Functions

(defn invert-rate
  "Invert an exchange rate (swap numerator and denominator).
   Returns rate with :/ keyword (canonical form).

   Accepts any division operator (:/, '/, \"/\").

   Example:
     (invert-rate [:/ [0.032 :usd] [1 :hash]])
     => [:/ [31.25 :hash] [1 :usd]]"
  [[_ num denom]]
  [:/ denom num])

(defn compose-rates
  "Compose two rates for multi-hop conversion.
   Returns rate with :/ keyword (canonical form).

   Accepts any division operator (:/, '/, \"/\").

   Example: hash→usd + usd→btc = hash→btc
     (compose-rates
       [:/ [0.032 :usd] [1 :hash]]
       [:/ [0.00001 :btc] [1 :usd]])
     => [:/ [0.00000032 :btc] [1 :hash]]"
  [[_ [num1 unit1] [denom1 unit1-denom]]
   [_ [num2 unit2] [denom2 unit2-denom]]]
  (when-not (= unit1 unit2-denom)
    (throw (ex-info "Cannot compose rates - units don't chain"
                    {:rate1-numerator unit1
                     :rate2-denominator unit2-denom})))
  [:/ [(*' num1 num2) unit2] [(*' denom1 denom2) unit1-denom]])

(defn normalize-rate
  "Normalize rate to have denominator = 1.
   Returns rate with :/ keyword (canonical form).

   Accepts any division operator (:/, '/, \"/\").

   Example:
     (normalize-rate [:/ [3.2 :usd] [100 :hash]])
     => [:/ [0.032 :usd] [1 :hash]]"
  [[_ [num-amt num-unit] [denom-amt denom-unit]]]
  (if (= 1 denom-amt)
    [:/ [num-amt num-unit] [denom-amt denom-unit]]
    [:/ [(/ num-amt denom-amt) num-unit] [1 denom-unit]]))

(defn rate
  "Convenient rate constructor with natural syntax.

   Returns canonical vector rate structure: [:/ [num unit] [denom unit]]

   Signatures:
     (rate num-amt num-unit :per per-unit)           ; Implies denominator = 1
     (rate num-amt num-unit :per [denom-amt denom-unit])  ; Explicit denominator

   Examples:
     (rate 0.032 :usd :per :hash)
     => [:/ [0.032 :usd] [1 :hash]]

     (rate 31.25 :hash :per :usd)
     => [:/ [31.25 :hash] [1 :usd]]

     (rate 0.064 :usd :per [2 :hash])  ; Non-normalized
     => [:/ [0.064 :usd] [2 :hash]]

   Usage with token-convert:
     (token-convert [1000 :hash] :usd (rate 0.032 :usd :per :hash))
     => [32.0 :usd]

   Usage with portfolio-value:
     (portfolio-value
       [[1000 :hash] [10 :usd]]
       :usd
       [(rate 0.032 :usd :per :hash)])
     => [42.0 :usd]"
  [num-amt num-unit per-kw per-unit-or-vec]
  {:pre [(= per-kw :per)]}
  (if (vector? per-unit-or-vec)
    ;; Explicit denominator: [denom-amt denom-unit]
    (let [[denom-amt denom-unit] per-unit-or-vec]
      [:/ [num-amt num-unit] [denom-amt denom-unit]])
    ;; Implied denominator = 1
    (do
     (when-not (or (keyword? per-unit-or-vec) (string? per-unit-or-vec))
       (throw (ex-info "Per-unit must be a keyword, string, or [amount unit] vector"
                       {:provided per-unit-or-vec})))
     [:/ [num-amt num-unit] [1 per-unit-or-vec]])))

;; Compatible Units Registry
;; Defines which units can be converted between for same-token denominations
(def compatible-units
     "Registry of compatible unit conversions for same-token denominations.
   Maps normalized unit pairs to conversion rates."
     {#{:hash :nhash} [:/ [1 :hash] [1000000000 :nhash]]
      #{:btc :sats}   [:/ [1 :btc] [100000000 :sats]]})

(defn- find-compatible-rate
  "Find conversion rate between compatible units (e.g., hash/nhash, btc/sats).
   Returns normalized rate or nil if units are not compatible."
  [from-unit to-unit]
  (let [from-norm (normalize-unit from-unit)
        to-norm (normalize-unit to-unit)
        unit-set #{from-norm to-norm}]
    (when-let [rate (get compatible-units unit-set)]
      ;; Return rate in correct direction
              (let [[_ [_ num-unit] [_ denom-unit]] rate
                    num-unit-norm (normalize-unit num-unit)
                    denom-unit-norm (normalize-unit denom-unit)]
                (if (and (= from-norm denom-unit-norm) (= to-norm num-unit-norm))
                  rate  ; Already correct direction
                  (invert-rate rate)))))) ; Need to invert

(defn portfolio-value
  "Calculate total portfolio value by converting multiple token holdings to target currency.

   Arguments:
     holdings - Vector of token amounts: [[1000 :hash] [5E7 :nhash] [10 :usd]]
     to-unit - Target unit for aggregation: :usd, \"USD\", etc.
     rates - Vector of exchange rates (any format, any normalization)

   Features:
     - Auto-normalizes all rates to denominator = 1
     - Generates inverted rates for bidirectional matching (doubles coverage)
     - Handles non-normalized rates: [:/ [0.064 :usd] [2 :hash]]
     - Skips holdings already in target currency
     - Supports compatible-units registry for same-token denominations (hash/nhash, btc/sats)
     - Preserves target unit format in final result

   Examples:
     ;; Simple USD portfolio valuation
     (portfolio-value
       [[1000 :hash] [5E7 :nhash] [10 :usd]]
       :usd
       [[:/ [0.032 :usd] [1 :hash]]])
     => [42.6 :usd]  ; 1000*0.032 + 5E7*0.032/1E9 + 10

     ;; Aggregate hash denominations
     (portfolio-value
       [[1000 :hash] [5E7 :nhash]]
       :hash
       [])
     => [1050.0 :hash]  ; Uses compatible-units registry

     ;; Non-normalized rates
     (portfolio-value
       [[100 :hash]]
       :usd
       [[:/ [0.064 :usd] [2 :hash]]])
     => [3.2 :usd]  ; Normalizes to [:/ [0.032 :usd] [1 :hash]]"
  [holdings to-unit rates]
  ;; Validate inputs
  (when-not (vector? holdings)
    (throw (ex-info "Holdings must be a vector of token amounts"
                    {:provided holdings})))
  (when-not (every? token-amount? holdings)
    (throw (ex-info "All holdings must be valid token amounts [amount unit]"
                    {:invalid (remove token-amount? holdings)})))

  ;; Prepare rates: normalize + create inverted versions
  (let [to-unit-norm (normalize-unit to-unit)
        ;; Normalize all incoming rates
        normalized-rates (map normalize-rate rates)
        ;; Generate inverted rates for bidirectional matching
        inverted-rates (map invert-rate normalized-rates)
        ;; Combine both for auto-matching (doubles coverage)
        all-rates (concat normalized-rates inverted-rates)

        ;; Convert each holding to target currency
        converted-amounts
        (for [[amount from-unit] holdings]
             (let [from-unit-norm (normalize-unit from-unit)]
               (cond
              ;; Already in target currency - use as-is
                 (= from-unit-norm to-unit-norm)
                 amount

              ;; Find matching rate from provided rates
                 :else
                 (if-let [matching-rate
                          (first
                           (filter
                            (fn [[_ [_ num-unit] [_ denom-unit]]]
                              (let [num-norm (normalize-unit num-unit)
                                    denom-norm (normalize-unit denom-unit)]
                                (or (and (= from-unit-norm denom-norm)
                                         (= to-unit-norm num-norm))
                                    (and (= from-unit-norm num-norm)
                                         (= to-unit-norm denom-norm)))))
                            all-rates))]
                ;; Found matching rate - use token-convert
                         (first (token-convert [amount from-unit] to-unit matching-rate))

                ;; Try compatible-units registry for same-token denominations
                         (if-let [compatible-rate (find-compatible-rate from-unit to-unit)]
                                 (first (token-convert [amount from-unit] to-unit compatible-rate))

                  ;; No rate found
                                 (throw (ex-info "No conversion rate found for holding"
                                                 {:holding [amount from-unit]
                                                  :target to-unit-norm
                                                  :available-rates (vec all-rates)})))))))]

    ;; Sum all converted amounts and preserve target unit format
    [(reduce +' 0 converted-amounts) to-unit]))

;;=============================================================================
;; Phase 3E: Token Formatting Utilities
;;=============================================================================

;; Currency Symbol Registry
(def currency-symbols
     "Mapping of currency units to their display symbols"
     {:usd "$"
      :eur "€"
      :gbp "£"
      :jpy "¥"
      :btc "₿"
      :eth "Ξ"})

(defn- auto-decimals
  "Smart decimal place selection based on amount size.
   - Tiny amounts (<0.01): 8 decimals for precision
   - Small amounts (<1): 6 decimals
   - Standard currency (<1000): 2 decimals
   - Large amounts: 0 or 2 decimals based on fractional part"
  [amount]
  (cond
    (< amount 0.01) 8     ; Tiny amounts - show precision
    (< amount 1) 6        ; Small amounts
    (< amount 1000) 2     ; Standard currency
    :else                 ; Large amounts
    (let [frac (- amount (long amount))]
      (if (< frac 0.01) 0 2))))

(defn- format-with-separators
  "Format number with thousands and decimal separators.
   Returns string with proper separators applied."
  [num decimals thousands-sep decimal-sep]
  (let [factor (Math/pow 10 decimals)
        rounded (/ (Math/round (* (double num) factor)) factor)
        num-str (clojure.core/format (str "%." decimals "f") rounded)
        [whole-part frac-part] (str/split num-str #"\.")
        ;; Add thousands separators to whole part
        whole-with-sep (let [rev-whole (vec (reverse whole-part))
                             grouped (partition-all 3 rev-whole)
                             rev-grouped (map reverse grouped)]
                         (apply str (reverse (interpose thousands-sep (map #(apply str %) rev-grouped)))))]
    (if (and frac-part (> decimals 0))
      (str whole-with-sep decimal-sep frac-part)
      whole-with-sep)))

(defn format-token
  "Format token amount for human-readable presentation.

   Signature:
     (format-token [amount unit])
     (format-token [amount unit] options)

   Options:
     :decimals      - nil (auto) or 0-8 for explicit decimal places
     :symbol        - true to show currency symbol (default: true)
     :uppercase     - true to uppercase unit (default: true)
     :thousands-sep - thousands separator character (default: \",\")
     :decimal-sep   - decimal separator character (default: \".\")
     :components    - true to return component map instead of string

   Examples:
     (format-token [1.750000000000403E7 \"hash\"])
     => \"17,500,000 HASH\"

     (format-token [32.156789 :usd])
     => \"$32.16 USD\"

     (format-token [123456789.12 :usd] {:components true})
     => {:type :token
         :amt \"123,456,789.12\"
         :unit \"USD\"
         :symbol \"$\"
         :formatted \"$123,456,789.12 USD\"
         :raw-amount 123456789.12}"
  ([token-tuple]
   (format-token token-tuple {}))
  ([token-tuple options]
   (when-not (token-amount? token-tuple)
     (throw (ex-info "Argument must be a token amount [amount unit]"
                     {:provided token-tuple})))

   (let [[amount unit] token-tuple
         unit-norm (normalize-unit unit)
         decimals (or (:decimals options) (auto-decimals amount))
         symbol? (get options :symbol true)
         uppercase? (get options :uppercase true)
         thousands-sep (or (:thousands-sep options) ",")
         decimal-sep (or (:decimal-sep options) ".")
         components? (:components options)

         ;; Format amount
         amt-str (format-with-separators amount decimals thousands-sep decimal-sep)

         ;; Format unit
         unit-str (if uppercase?
                    (.toUpperCase (name unit-norm))
                    (name unit-norm))

         ;; Get symbol if available
         symbol-str (when symbol? (get currency-symbols unit-norm))

         ;; Build formatted string
         formatted (str (when symbol-str (str symbol-str))
                        amt-str
                        " "
                        unit-str)]

     (if components?
       {:type :token
        :amt amt-str
        :unit unit-str
        :symbol symbol-str
        :formatted formatted
        :raw-amount amount}
       formatted))))

(defn format-rate
  "Format exchange rate for human-readable presentation.

   Signature:
     (format-rate rate-tuple)
     (format-rate rate-tuple options)

   Automatically normalizes rates to denominator = 1 for cleaner display.

   Options:
     :decimals      - nil (auto) or 0-8 for explicit decimal places
     :symbol        - true to show currency symbol (default: true)
     :uppercase     - true to uppercase unit (default: true)
     :thousands-sep - thousands separator character (default: \",\")
     :decimal-sep   - decimal separator character (default: \".\")
     :style         - :per (default, \"X per Y\") or :slash (\"X/Y\")
     :components    - true to return component map instead of string

   Examples:
     (format-rate [:/ [0.032 :usd] [1 :hash]])
     => \"$0.032 per HASH\"

     (format-rate [:/ [0.032 :usd] [1 :hash]] {:style :slash})
     => \"USD/HASH\"

     (format-rate [:/ [31.25 :hash] [1 :usd]])
     => \"31.25 HASH per USD\"

     (format-rate [:/ [0.064 :usd] [2 :hash]])
     => \"$0.032 per HASH\"  ; Auto-normalized

     (format-rate [:/ [0.032 :usd] [1 :hash]] {:components true})
     => {:type :rate
         :numerator {:amt \"0.032\" :unit \"USD\" :symbol \"$\"}
         :denominator {:amt \"1\" :unit \"HASH\" :symbol nil}
         :formatted-per \"$0.032 per HASH\"
         :formatted-slash \"USD/HASH\"
         :formatted \"$0.032 per HASH\"
         :raw-rate [:/ [0.032 :usd] [1 :hash]]}"
  ([rate-tuple]
   (format-rate rate-tuple {}))
  ([rate-tuple options]
   ;; Validate rate
   (let [validation (valid-rate? rate-tuple)]
     (when-not (:valid validation)
       (throw (ex-info "Invalid rate" validation))))

   ;; Normalize rate first for cleaner display
   (let [normalized-rate (normalize-rate rate-tuple)
         [_ [num-amt num-unit] [denom-amt denom-unit]] normalized-rate

         num-unit-norm (normalize-unit num-unit)
         denom-unit-norm (normalize-unit denom-unit)

         ;; Format options
         decimals (or (:decimals options) (auto-decimals num-amt))
         symbol? (get options :symbol true)
         uppercase? (get options :uppercase true)
         thousands-sep (or (:thousands-sep options) ",")
         decimal-sep (or (:decimal-sep options) ".")
         style (or (:style options) :per)
         components? (:components options)

         ;; Format numerator
         num-amt-str (format-with-separators num-amt decimals thousands-sep decimal-sep)
         num-unit-str (if uppercase?
                        (.toUpperCase (name num-unit-norm))
                        (name num-unit-norm))
         num-symbol-str (when symbol? (get currency-symbols num-unit-norm))

         ;; Format denominator
         denom-decimals (or (:decimals options) (auto-decimals denom-amt))
         denom-amt-str (format-with-separators denom-amt denom-decimals thousands-sep decimal-sep)
         denom-unit-str (if uppercase?
                          (.toUpperCase (name denom-unit-norm))
                          (name denom-unit-norm))
         denom-symbol-str (when symbol? (get currency-symbols denom-unit-norm))

         ;; Build formatted strings
         ;; :per style - "$0.032 per HASH" (shows amounts and symbols)
         formatted-per (str (when num-symbol-str num-symbol-str)
                            num-amt-str
                            " "
                            num-unit-str
                            " per "
                            denom-unit-str)

         ;; :slash style - "USD/HASH" (units only, no amounts or symbols)
         formatted-slash (str num-unit-str "/" denom-unit-str)

         ;; Select format based on style
         formatted (case style
                     :slash formatted-slash
                     :per formatted-per
                     formatted-per)]  ; default to :per

     (if components?
       {:type :rate
        :numerator {:amt num-amt-str
                    :unit num-unit-str
                    :symbol num-symbol-str}
        :denominator {:amt denom-amt-str
                      :unit denom-unit-str
                      :symbol denom-symbol-str}
        :formatted-per formatted-per
        :formatted-slash formatted-slash
        :formatted formatted
        :raw-rate rate-tuple}
       formatted))))

(defn format
  "Format token amounts or exchange rates with auto-detection.

   Auto-detects type based on structure:
   - 2-element vector [amount unit] → token amount
   - 3-element vector [:/ [num unit] [denom unit]] → exchange rate

   Signature:
     (format value)
     (format value options)

   Options:
     :decimals      - nil (auto) or 0-8 for explicit decimal places
     :symbol        - true to show currency symbol (default: true)
     :uppercase     - true to uppercase unit (default: true)
     :thousands-sep - thousands separator character (default: \",\")
     :decimal-sep   - decimal separator character (default: \".\")
     :components    - true to return component map instead of string

   Examples:
     ;; Token amounts (auto-detected)
     (format [1.750000000000403E7 \"hash\"])
     => \"17,500,000 HASH\"

     (format [32.156789 :usd])
     => \"$32.16 USD\"

     ;; Exchange rates (auto-detected)
     (format [:/ [0.032 :usd] [1 :hash]])
     => \"$0.032 per HASH\"

     (format [:/ [31.25 :hash] [1 :usd]])
     => \"31.25 HASH per USD\"

     ;; Component maps
     (format [123456.78 :usd] {:components true})
     => {:type :token
         :amt \"123,456.78\"
         :unit \"USD\"
         :symbol \"$\"
         :formatted \"$123,456.78 USD\"
         :raw-amount 123456.78}

     (format [:/ [0.032 :usd] [1 :hash]] {:components true})
     => {:type :rate
         :numerator {:amt \"0.032\" :unit \"USD\" :symbol \"$\"}
         :denominator {:amt \"1\" :unit \"HASH\" :symbol nil}
         :formatted \"$0.032 per HASH\"
         :raw-rate [:/ [0.032 :usd] [1 :hash]]}"
  ([value]
   (format value {}))
  ([value options]
   (cond
     ;; 3-element vector starting with :/ -> rate
     (and (vector? value)
          (= 3 (count value))
          (#{:/ '/ "/"} (first value)))
     (format-rate value options)

     ;; 2-element vector -> token amount
     (and (vector? value) (= 2 (count value)))
     (format-token value options)

     :else
     (throw (ex-info "Value must be token amount [amount unit] or rate [:/ [num unit] [denom unit]]"
                     {:provided value})))))

;; Add token conversion functions to math-fns for use in expressions
(def token-conversion-fns
     {'token-convert token-convert
      'portfolio-value portfolio-value
      'rate rate
      'valid-rate? valid-rate?
      'invert-rate invert-rate
      'compose-rates compose-rates
      'normalize-rate normalize-rate
      'normalize-unit normalize-unit
      'token-amount token-amount
      'token-amount? token-amount?
      'get-amount get-amount
      'get-unit get-unit
      'format format
      'format-token format-token
      'format-rate format-rate})

;; Merge all functions for SCI context
(def all-math-fns
     (merge math-fns token-conversion-fns))

;; SCI context for safe evaluation
;; Note: No :allow list - we want to allow our math-fns bindings
;; No :deny list - security is non-issue (nrepl-eval already allows arbitrary code)
(def sci-ctx
     (sci/init
      {:bindings all-math-fns
       :realize-max 10000}))  ; prevent infinite sequences

(defn- enhance-error-message
  "Enhance error messages with contextual hints based on error patterns"
  [error-msg expr]
  (let [msg (.toLowerCase error-msg)]
    (cond
      ;; Division by zero
      (or (re-find #"divide.*zero" msg)
          (re-find #"infinity" msg))
      {:error error-msg
       :hint "Division by zero detected. Check denominator values."
       :suggestion "Use conditional logic: (if (zero? x) 0 (/ y x))"}

      ;; Undefined symbol
      (re-find #"unable to resolve symbol" msg)
      (let [symbol (second (re-find #"symbol: (\S+)" msg))]
        {:error error-msg
         :hint (str "Function '" symbol "' not found.")
         :suggestion "Check available functions or use 'let' to define variables."
         :available-help "Common functions: +, -, *, /, sqrt, pow, sin, cos, mean, etc."})

      ;; Wrong number of arguments
      (re-find #"wrong number of args" msg)
      {:error error-msg
       :hint "Function called with incorrect number of arguments."
       :suggestion "Check function signature. Example: (pow base exponent) needs 2 args."}

      ;; Type casting errors
      (or (re-find #"cannot be cast" msg)
          (re-find #"class.*cannot be cast" msg))
      {:error error-msg
       :hint "Type mismatch - trying to use incompatible types."
       :suggestion "Ensure numeric values: use (double x) or check input types."}

      ;; Invalid vector/sequence operations
      (re-find #"don't know how to create" msg)
      {:error error-msg
       :hint "Invalid data structure syntax."
       :suggestion "Use vectors [1 2 3] or sequences with functions: (mean [1 2 3])"}

      ;; Date/time parsing errors
      (re-find #"parse" msg)
      {:error error-msg
       :hint "Date/time parsing failed."
       :suggestion "Use ISO format 'YYYY-MM-DD' or unix timestamps (number)."}

      ;; Negative sqrt/log errors
      (re-find #"nan" msg)
      {:error error-msg
       :hint "Mathematical operation resulted in NaN (Not a Number)."
       :suggestion "Check for negative sqrt/log arguments or invalid operations."}

      ;; DeFi specific errors
      (and (re-find #"zero" msg)
           (or (re-find #"pool" expr)
               (re-find #"reserve" expr)
               (re-find #"liquidity" expr)))
      {:error error-msg
       :hint "DeFi calculation error - likely zero pool reserves."
       :suggestion "Ensure pool reserves and liquidity values are non-zero."}

      ;; Generic fallback
      :else
      {:error error-msg
       :hint "Expression evaluation failed."
       :suggestion "Check syntax: use prefix notation like (+ 1 2) not (1 + 2)."})))

(defn calculate
  "Evaluate mathematical expression with timeout protection.
   Returns {:result ... :type ... :expr ...} or {:error ... :type ... :expr ...}

   Timeout protection is CRITICAL for synchronous MCP interface - prevents hanging."
  [expr-string]
  (let [timeout-ms 5000
        result-promise (promise)]
    (future
     (try
      (deliver result-promise
               (let [result (sci/eval-string* sci-ctx expr-string)]
                 {:result result
                  :type (str (type result))
                  :expr expr-string}))
      (catch Exception e
             (let [enhanced (enhance-error-message (.getMessage e) expr-string)]
               (deliver result-promise
                        (merge {:expr expr-string
                                :type "error"}
                               enhanced))))))
    (deref result-promise timeout-ms
           {:error "Calculation timeout (>5s)"
            :type "timeout"
            :expr expr-string
            :hint "Expression took too long to evaluate (>5 seconds)."
            :suggestion "Simplify expression or check for infinite loops."})))
