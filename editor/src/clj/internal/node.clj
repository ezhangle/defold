(ns internal.node
  (:require [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [internal.util :as util]
            [internal.cache :as c]
            [internal.graph.types :as gt]
            [internal.graph.error-values :as ie]
            [plumbing.core :as pc]
            [plumbing.fnk.pfnk :as pf]
            [schema.core :as s]
            [clojure.walk :as walk]
            [clojure.zip :as zip])
  (:import [internal.graph.types IBasis]
           [internal.graph.error_values ErrorValue]
           [clojure.lang Named]
           [schema.core Maybe Either]))

;; TODO - replace use of 'transform' as a variable name with 'label'

(set! *warn-on-reflection* true)

(def ^:dynamic *node-value-debug* nil)
(def ^:dynamic ^:private *node-value-nesting* 0)

(defn nodevalstr [this node-type label & t]
  (apply str *node-value-nesting* "\t" (:_node-id this) "\t"  (:name @node-type) label "\t" t))

(prefer-method clojure.pprint/code-dispatch clojure.lang.IPersistentMap clojure.lang.IDeref)
(prefer-method clojure.pprint/simple-dispatch clojure.lang.IPersistentMap clojure.lang.IDeref)

(defprotocol Ref
  (ref-key [this]))

(defn ref? [x] (and (extends? Ref (class x)) x))

(defprotocol Type
  (describe* [type])
  (ref*      [type]))

(defn type? [x] (and (extends? Type (class x)) x))
(defn- named? [x] (instance? clojure.lang.Named x))

;;; ----------------------------------------
;;; Node type definition
(declare node-type-resolve value-type-resolve)

(defn has-flag? [flag entry]
  (contains? (:flags entry) flag))

(def injectable?         (partial has-flag? :inject))
(def cached?             (partial has-flag? :cached))
(def cascade-deletes?    (partial has-flag? :cascade-delete))
(def extern?             (partial has-flag? :unjammable))
(def internal?           (partial has-flag? :internal))
(def explicit?           (partial has-flag? :explicit))
(def external-property?  (complement internal?))

(def internal-keys #{:_node-id :_declared-properties :_properties :_output-jammers})

(defn- filterm [pred m]
  (into {} (filter pred m)))

(defprotocol NodeType)

(defn node-type? [x] (satisfies? NodeType x))

(defrecord NodeTypeRef [k]
  Ref
  (ref-key [this] k)

  clojure.lang.IDeref
  (deref [this]
    (node-type-resolve k)))

(defrecord NodeTypeImpl [name supertypes output input property input-dependencies property-display-order]
  NodeType
  Type
  (describe*              [this] (pr-str (select-keys this [:input :output :property])))
  (ref*                   [this] (->NodeTypeRef key)))

;;; accessors for node type information

(defn supertypes             [nt]        (some-> nt deref :supertypes))
(defn property-display-order [nt]        (some-> nt deref :property-display-order))
(defn transforms             [nt]        (some-> nt deref :output))     ;; deprecated
(defn transform-types        [nt]        (some-> nt deref :output (->> (util/map-vals :value-type)))) ;; deprecated
(defn declared-properties    [nt]        (some-> nt deref :property (->> (remove (comp internal? val)) (into {})))) ;; deprecated
(defn internal-properties    [nt]        (some-> nt deref :property (->> (filter (comp internal? val)) (into {}))))
(defn declared-inputs        [nt]        (some-> nt deref :input))
(defn injectable-inputs      [nt]        (some-> nt deref :input (->> (filterm #(injectable? (val %))) util/key-set)))
(defn declared-outputs       [nt]        (some-> nt deref :output))
(defn cached-outputs         [nt]        (some-> nt deref :output (->> (filterm #(cached? (val %))) util/key-set)))
(defn input-dependencies     [nt]        (some-> nt deref :input-dependencies))
(defn substitute-for         [nt label]  (some-> nt deref (get-in [:input label :options :substitute])))
(defn input-type             [nt label]  (some-> nt deref (get-in [:input label :value-type])))
(defn input-cardinality      [nt label]  (if (has-flag? :array (get-in (deref nt) [:input label])) :many :one))
(defn behavior               [nt label]  (some-> nt deref (get-in [:behavior label])))
(defn cascade-deletes        [nt]        (some-> nt deref :input (->> (filterm #(cascade-deletes? (val %))) util/key-set)))
(defn output-type            [nt label]  (some-> nt deref (get-in [:output label :value-type])))
(defn output-arguments       [nt label]  (some-> nt deref (get-in [:output label :arguments])))
(defn externs                [nt]        (some-> nt deref :property (->> (filterm #(extern? (val %))) util/key-set)))
(defn property-type          [nt label]  (some-> nt deref (get-in [:property label :value-type])))
(defn has-input?             [nt label]  (some-> nt deref (get :input) (contains? label)))
(defn has-output?            [nt label]  (some-> nt deref (get :output) (contains? label)))
(defn has-property?          [nt label]  (some-> nt deref (get :property) (contains? label)))
(defn property-labels        [nt]        (util/key-set (declared-properties nt)))
(defn input-labels           [nt]        (util/key-set (declared-inputs nt)))
(defn output-labels          [nt]        (util/key-set (declared-outputs nt)))
(def  public-properties   declared-properties)


;;; ----------------------------------------
;;; Registry of node types

(defn- type-resolve
  [reg k]
  (loop [type-name k]
    (cond
      (ref? type-name)           (recur (get reg (ref-key type-name)))
      (named? type-name)         (recur (get reg type-name))
      (symbol? type-name)        (recur (util/vgr type-name))
      (type? type-name)          type-name
      (util/protocol? type-name) type-name
      (class? type-name)         type-name
      :else                      nil)))

(defn register-type
  [reg-ref k type]
  (assert (named? k))
  (assert (or (type? type) (named? type) (util/schema? type) (util/protocol? type) (class? type)) (pr-str k type))
  (swap! reg-ref assoc k type)
  k)

(defn unregister-type
  [reg-ref k]
  (swap! reg-ref dissoc k))

(defonce ^:private node-type-registry-ref (atom {}))

(defn node-type-registry [] @node-type-registry-ref)

(defn node-type-resolve  [k] (type-resolve (node-type-registry) k))

(defn node-type [x]
  (cond
    (type? x)  x
    (named? x) (or (node-type-resolve x) (throw (Exception. (str "Unable to resolve node type: " (pr-str x)))))
    :else      (throw (Exception. (str (pr-str x) " is not a node type")))))

(defn register-node-type
  [k node-type]
  (assert (node-type? node-type))
  (->NodeTypeRef (register-type node-type-registry-ref k node-type)))

;;; ----------------------------------------
;;; Value type definition

(defprotocol ValueType)

(defrecord ValueTypeImpl [name schema]
  ValueType
  Type
  (describe* [this] (s/explain schema))

  schema.core/Schema
  (spec [this] (s/spec schema))
  (explain [this] (s/explain schema)))

(extend-protocol ValueType java.lang.Class)

(extend-protocol Type
  java.lang.Class
  (describe* [this] (.getName this)))

(defn value-type? [x] (satisfies? ValueType x))

;;; ----------------------------------------
;;; Registry of value types

(defonce ^:private value-type-registry-ref (atom {}))

(defn value-type-registry [] @value-type-registry-ref)

(defrecord ValueTypeRef [k]
  Ref
  (ref-key [this] k)

  schema.core/Schema
  (spec [this] (s/spec (deref this)))
  (explain [this] (s/explain (deref this)))

  clojure.lang.IDeref
  (deref [this]
    (value-type-resolve k)))

(prefer-method clojure.core/print-method ValueTypeRef clojure.lang.IDeref)

(defn- mangle
  "Convert a Clojure symbol into a (hypothetical) class name"
  [x]
  (if-let [ns (namespace x)]
    (let [pkg (str/replace ns #"-" "_")]
      (symbol (str pkg "." (name x))))
    x))

(defn- class-or-protocol? [x]
  (cond
    (class? x)                    x
    (util/protocol? x)            x
    (and (symbol? x) (resolve x)) (class-or-protocol? (resolve x))
    (symbol? x)                   (class-or-protocol? (resolve (mangle x)))
    (and (var? x) (var-get x))    (class-or-protocol? (var-get x))))

(defn register-value-type
  [k value-type]
  (let [real-value-type (cond
                          (value-type? value-type)    value-type
                          (named? value-type)         value-type
                          (util/protocol? value-type) (:on-interface value-type)
                          (util/schema? value-type)   value-type)]
    (assert real-value-type)
    (->ValueTypeRef (register-type value-type-registry-ref k real-value-type))))

(defn unregister-value-type
  [k]
  (unregister-type value-type-registry-ref k))

(defn k->s [k] (symbol (namespace k) (name k)))

(defn value-type-resolve [k]
  (or
   (type-resolve (value-type-registry) k)
   (when-let [v (class-or-protocol? (k->s k))]
     (deref (register-value-type k v)))))

(defn- vt->s [vt]
  (if (class? vt) vt (:schema vt)))

(defn value-type-schema
  [vtr]
  (cond
    (class? vtr)   vtr
    (ref? vtr)     (vt->s @vtr)
    (keyword? vtr) (vt->s (value-type-resolve vtr))))

;;; ----------------------------------------
;;; Construction support

(defrecord NodeImpl [node-type]
  gt/Node
  (node-id [this]
    (:_node-id this))

  (node-type [_ _]
    node-type)

  (get-property [this basis property]
    (get this property))

  (set-property [this basis property value]
    (assert (contains? (-> node-type deref :property util/key-set) property)
            (format "Attempting to use property %s from %s, but it does not exist"
                    property (:name node-type)))
    (assoc this property value))

  gt/Evaluation
  (produce-value [this label evaluation-context]
    (let [beh (behavior node-type label)]
      (assert beh (str "No such output, input, or property " label
                            " exists for node " (gt/node-id this)
                            " of type " (:name node-type)
                            "\nIn production: " (get evaluation-context :in-production)))
      (when *node-value-debug*
        (println (nodevalstr this node-type label)))
      (binding [*node-value-nesting* (inc *node-value-nesting*)]
        ((:fn beh) this evaluation-context))))

  gt/OverrideNode
  (clear-property [this basis property]
    (throw (ex-info (str "Not possible to clear property " property
                         " of node type " (:name node-type)
                         " since the node is not an override")
                    {:label property :node-type node-type})))

  (original [this]
    nil)

  (set-original [this original-id]
    (throw (ex-info "Originals can't be changed for original nodes")))

  (override-id [this]
    nil))

(defn defaults
  "Return a map of default values for the node type."
  [node-type-ref]
  (util/map-vals #(some-> % :default :fn util/var-get-recursive (util/apply-if-fn {}))
                 (public-properties node-type-ref)))

(defn- assert-no-extra-args
  [node-type-ref args]
  (let [args-without-properties (set/difference
                                 (util/key-set args)
                                 (util/key-set (:property (deref node-type-ref))))]
    (assert (empty? args-without-properties) (str "You have given values for properties " args-without-properties ", but those don't exist on nodes of type " (:name node-type-ref)))))

(defn construct
  [node-type-ref args]
  (assert (and node-type-ref (deref node-type-ref)))
  (assert-no-extra-args node-type-ref args)
  (-> (new internal.node.NodeImpl node-type-ref)
      (merge (defaults node-type-ref))
      (merge args)))

;;; ----------------------------------------
;;; Evaluating outputs

(defn without [s exclusions] (reduce disj s exclusions))

(defn- all-properties
  [node-type]
  (declared-properties node-type))

(defn- all-labels
  [node-type]
  (set/union (util/key-set (:output node-type)) (util/key-set (:input node-type))))

(def ^:private special-labels #{:_declared-properties})

(defn ordinary-output-labels
  [description]
  (without (util/key-set (:output description)) special-labels))

(defn ordinary-input-labels
  [description]
  (without (util/key-set (:input description)) (util/key-set (:output description))))

(defn- ordinary-property-labels
  [node-type]
  (without (util/key-set (declared-properties node-type)) special-labels))

(defn- node-value*
  [node-or-node-id label evaluation-context]
  (let [cache              (:cache evaluation-context)
        basis              (:basis evaluation-context)
        node               (if (gt/node-id? node-or-node-id) (gt/node-by-id-at basis node-or-node-id) node-or-node-id)
        result             (and node (gt/produce-value node label evaluation-context))]
    (when (and node cache)
      (c/cache-hit cache @(:hits evaluation-context))
      (c/cache-encache cache @(:local evaluation-context)))
    result))

(defn make-evaluation-context
  [cache basis ignore-errors skip-validation caching?]
  {:local           (atom {})
   :cache           (when caching? cache)
   :snapshot        (if caching? (c/cache-snapshot cache) {})
   :hits            (atom [])
   :basis           basis
   :in-production   #{}
   :ignore-errors   ignore-errors
   :skip-validation skip-validation
   :caching?        caching?})

(defn node-value
  "Get a value, possibly cached, from a node. This is the entry point
  to the \"plumbing\". If the value is cacheable and exists in the
  cache, then return that value. Otherwise, produce the value by
  gathering inputs to call a production function, invoke the function,
  maybe cache the value that was produced, and return it."
  [node-or-node-id label {:keys [cache ^IBasis basis ignore-errors skip-validation in-transaction?] :or {ignore-errors 0} :as options}]
  (let [caching?           (and (not (:no-cache options)) cache)
        evaluation-context (make-evaluation-context cache basis ignore-errors skip-validation caching?)]
    (node-value* node-or-node-id label evaluation-context)))

(def ^:dynamic *suppress-schema-warnings* false)

(defn warn-input-schema [node-id node-type label value input-schema error]
  (when-not *suppress-schema-warnings*
    (println "Schema validation failed for node " node-id "(" node-type " ) label " label)
    (println "There were " (count (vals error)) " problems")
    (let [explanation (s/explain input-schema)]
      (doseq [[key val] error]
        (println "Argument " key " which is")
        (pp/pprint value)
        (println "should match")
        (pp/pprint (get explanation key))
        (println "but it failed because ")
        (pp/pprint val)
        (println)))))

(defn warn-output-schema [node-id node-type label value output-schema error]
  (when-not *suppress-schema-warnings*
    (println "Schema validation failed for node " node-id "(" node-type " ) label " label)
    (println "Output value:" value)
    (println "Should match:" (s/explain output-schema))
    (println "But:" error)))

(defn setter-for [node-type property]
  (some-> (public-properties node-type) property :setter :fn util/var-get-recursive))

;;; ----------------------------------------
;; Type checking

(defn- check-single-type
  [out in]
  (or
   (= s/Any in)
   (= out in)
   (and (class? in) (class? out) (.isAssignableFrom ^Class in out))))

(defn type-compatible?
  [output-typeref input-typeref]
  (let [output-schema (value-type-schema output-typeref)
        input-schema  (value-type-schema input-typeref)
        out-t-pl? (coll? output-schema)
        in-t-pl?  (coll? input-schema)]
    (or
     (= s/Any input-schema)
     (and out-t-pl? (= [s/Any] input-schema))
     (and (= out-t-pl? in-t-pl? true) (check-single-type (first output-schema) (first input-schema)))
     (and (= out-t-pl? in-t-pl? false) (check-single-type output-schema input-schema))
     (and (instance? Maybe input-schema) (type-compatible? output-schema (:schema input-schema)))
     (and (instance? Either input-schema) (some #(type-compatible? output-schema %) (:schemas input-schema))))))

(defn assert-type-compatible
  [basis src-id src-label tgt-id tgt-label]
  (let [output-nodetype (gt/node-type (gt/node-by-id-at basis src-id) basis)
        output-valtype  (output-type output-nodetype src-label)
        input-nodetype  (gt/node-type (gt/node-by-id-at basis tgt-id) basis)
        input-valtype   (input-type input-nodetype tgt-label)]
    (assert output-valtype
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s does not have an output or property named %s"
                    src-id (:name @output-nodetype) src-label
                    tgt-id (:name @input-nodetype) tgt-label
                    (:name @output-nodetype) src-label))
    (assert input-valtype
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s does not have an input named %s"
                    src-id (:name @output-nodetype) src-label
                    tgt-id (:name @input-nodetype) tgt-label
                    (:name @input-nodetype) tgt-label))
    (assert (type-compatible? output-valtype input-valtype)
            (format "Attempting to connect %s (a %s) %s to %s (a %s) %s, but %s and %s are not have compatible types."
                    src-id (:name @output-nodetype) src-label
                    tgt-id (:name @input-nodetype) tgt-label
                    (:k output-valtype) (:k input-valtype)))))


;;; ----------------------------------------
;;; Node type implementation

(defn- alias-of [ns s]
  (get (ns-aliases ns) s))

(defn localize
  ([ctor s]   (ctor (str *ns*) s))
  ([ctor n s] (ctor n s)))

(defn canonicalize [x]
  (cond
    (and (symbol? x) (namespace x))
    (do (assert (resolve x) (str "Unable to resolve symbol: " (pr-str x) " in this context"))
        (if-let [n (alias-of *ns* (symbol (namespace x)))]
          (symbol (str n) (name x))
          x))

    (and (symbol? x) (not (namespace x)))
    (do
      (assert (resolve x) (str "Unable to resolve symbol: " (pr-str x) " in this context"))
      (symbol (str *ns*) (name x)))

    (and (keyword? x) (namespace x))
    (if-let [n (alias-of *ns* (symbol (namespace x)))]
      (keyword (str n) (name x))
      x)

    :else
    x))

(defn- display-group?
  "Return true if the coll is a display group.
   A display group is a vector with a string label in the first position."
  [label coll]
  (and (vector? coll) (= label (first coll))))

(defn- display-group
  "Find a display group with the given label in the order."
  [order label]
  (first (filter #(display-group? label %) order)))

(defn join-display-groups
  "Given a display group and an 'order' in the rhs, see if there is a
  display group with the same label in the rhs. If so, attach its
  members to the original display group."
  [[label & _ :as lhs] rhs]
  (let [group-in-rhs (display-group rhs label)]
    (vec (into lhs (rest group-in-rhs)))))

(defn- expand-node-types
  "Replace every symbol that refers to a node type with the display
  order of that node type. E.g., given node BaseNode with display
  order [:a :b :c], then the input [:x :y BaseNode :z]
  becomes [:x :y :a :b :c :z]"
  [coll]
  (flatten
   (map #(cond
           (ref? %)    (property-display-order %)
           (named? %)  (if-let [nt (node-type-resolve (keyword (canonicalize %)))]
                         (:property-display-order nt)
                         %)
           :else       %)
        coll)))

(defn node-type-name? [x]
  (node-type-resolve (keyword (and (named? x) (canonicalize x)))))

(defn merge-display-order
  "Premise: intelligently merge the right-hand display order into the left hand one.
   Rules for merging are as follows:

   - A keyword on the left is left alone.
   - Any keywords on the right that do not appear on the left are appended to the end.
   - A vector with a string in the first position is a display group.
   - A display group on the left remains in the same relative position.
   - A display group on the left is recursively merged with a display group on the right that has the same label.

  When more than two display orders are given, each one is merged into the left successively."
  ([order] order)
  ([order1 order2]
   (loop [result []
          left   order1
          right  order2]
     (if-let [elem (first left)]
       (let [elem (if (node-type-name? elem) (canonicalize elem) elem)]
         (cond
           (node-type-name? elem)
           (recur result (concat (expand-node-types [elem]) (next left)) right)

           (keyword? elem)
           (recur (conj result elem) (next left) (remove #{elem} right))

           (sequential? elem)
           (let [header (first elem)
                 group  (next elem)]
             (if (some node-type-name? elem)
               (recur result (cons (expand-node-types elem) (next left)) right)
               (let [group-label   header
                     group-member? (set group)]
                 (recur (conj result (join-display-groups elem right))
                        (next left)
                        (remove #(or (group-member? %) (display-group? group-label %)) right)))))))
       (into result right))))
  ([order1 order2 & more]
   (reduce merge-display-order (merge-display-order order1 order2) more)))

(def assert-symbol (partial util/assert-form-kind "defnode" "symbol" symbol?))

(defn assert-value-type
  [where form]
  (let [pred (fn [f]
               (let [val (if (symbol? f) (util/vgr f) f)]
                 (value-type? (value-type-resolve val))))]
    (util/assert-form-kind "defnode" "registered value type"
                           pred
                           where form)))

;;; ----------------------------------------
;;; Parsing defnode forms

(defn dummy-produce-declared-properties []
  `(fn [] (assert false "This is a dummy function. You're probably looking for declared-properties-function.")))

(defn- all-available-arguments
  [description]
  (set/union #{:this :basis}
             (util/key-set (:input description))
             (util/key-set (:property description))
             (util/key-set (:output description))))

(defn verify-inputs-for-dynamics
  [description]
  (let [available-arguments (all-available-arguments description)]
    (doseq [[property-name property-type]       (:property description)
            [dynamic-name {:keys [arguments]}]  (:dynamics property-type)
            :let     [missing-args (set/difference arguments available-arguments)]]
      (assert (empty? missing-args)
              (str "Node " (:name description) " must have inputs or properties for the label(s) "
                   missing-args ", because they are needed by its property '" (name property-name) "'."))))
  description)

(defn verify-inputs-for-outputs
  [description]
  (let [available-arguments (all-available-arguments description)]
   (doseq [[output {:keys [arguments]}] (:output description)
           :let [missing-args (set/difference arguments available-arguments)]]
     (assert (empty? missing-args)
             (str "Node " (:name description) " must have inputs, properties or outputs for the label(s) "
                  missing-args ", because they are needed by the output '" (name output) "'."))))
  description)

(defn verify-labels
  [description]
  (let [inputs     (util/key-set (:input description))
        properties (util/key-set (:property description))
        collisions (set/intersection inputs properties)]
    (assert (empty? collisions) (str "inputs and properties can not be overloaded (problematic fields: " (str/join "," (map #(str "'" (name %) "'") collisions)) ")")))
  description)

(defn invert-map
  [m]
  (apply merge-with into
         (for [[k vs] m
               v vs]
           {v #{k}})))

(defn dependency-seq
  ([desc inputs]
   (dependency-seq desc #{} inputs))
  ([desc seen inputs]
   (disj
    (reduce
     (fn [dependencies argument]
       (conj
        (if (not (seen argument))
          (if-let [recursive (get-in desc [:output argument :arguments])]
            (into dependencies (dependency-seq desc (conj seen argument) recursive))
            dependencies)
          dependencies)
        argument))
     #{}
     inputs)
    :this)))

(defn description->input-dependencies
  [{:keys [output] :as description}]
  (let [outputs (zipmap (keys output)
                        (map #(dependency-seq description (:arguments %)) (vals output)))]
    (invert-map outputs)))

(defn attach-input-dependencies
  [description]
  (assoc description :input-dependencies (description->input-dependencies description)))

(defn input-dependencies-non-transitive
  "Return a map from input to affected outputs, but without including
  the transitive effects on other outputs within the same node
  type. This is a specialized case and if it's not apparent what it
  means, you should probably call input-dependencies instead."
  [node-type]
  (let [transforms (transforms node-type)]
    (invert-map
     (zipmap (keys transforms)
             (map util/inputs-needed (vals transforms))))))

(declare node-output-value-function)
(declare declared-properties-function)
(declare node-input-value-function)
(declare property-accessor-value-function)

(defn transform-outputs-plumbing-map [description]
  (let [labels  (ordinary-output-labels description)]
    (zipmap labels
            (map (fn [label]
                   {:fn (node-output-value-function description label)}) labels))))

(defn attach-output-behaviors
  [description]
  (update description :behavior merge (transform-outputs-plumbing-map description)))

(defn transform-inputs-plumbing-map [description]
  (let [labels  (ordinary-input-labels description)]
    (zipmap labels
            (map (fn [input] {:fn (node-input-value-function description input)}) labels))))

(defn attach-input-behaviors
  [description]
  (update description :behavior merge (transform-inputs-plumbing-map description)))

(defn transform-properties-plumbing-map
  [description]
  (let [result (reduce-kv (fn [m k v]
                            (assoc m k {:fn (property-accessor-value-function description k)}))
                          {}
                          (:property description))]
    result))

(defn attach-property-behaviors
  [description]
  (update description :property-behaviors #(merge (transform-properties-plumbing-map description) %)))

(defn- abstract-function
  [label type]
  (let [format-string (str "Node %d does not supply a production function for the abstract '" label "' output. Add (output " label " " type " your-function) to the definition")]
    `(pc/fnk [~'this]
             (throw (AssertionError.
                     (format ~format-string
                      (gt/node-id ~'this)))))))

(defn- parse-flags-and-options
  [allowed-flags allowed-options args]
  (loop [flags   #{}
         options {}
         args    args]
    (if-let [[arg & remainder] (seq args)]
      (cond
        (allowed-flags   arg) (recur (conj flags arg) options remainder)
        (allowed-options arg) (do (assert remainder (str "Expected value for option " arg))
                                  (recur flags (assoc options arg (first remainder)) (rest remainder)))
        :else                 [flags options args])
      [flags options args])))

(defn- class-symbol->vtr
  [sym]
  (let [cls (resolve sym)]
    (register-value-type (keyword (.getName ^Class cls)) cls)))

(defn- clj-symbol->vtr
  [sym]
  (->ValueTypeRef (keyword (canonicalize sym))))

(defn parse-type-form
  [where original-form]
  (let [multivalued? (vector? original-form)
        form         (if multivalued? (first original-form) original-form)
        type         (cond
                       (ref? form)                   form
                       (and (symbol? form)
                            (class? (resolve form))) (class-symbol->vtr form)
                       (named? form)                 (clj-symbol->vtr form)
                       :else                         nil)]
    (assert (not (nil? type))
            (str "defnode " where " requires a value type but was supplied with '"
                 original-form "' which cannot be used as a type"))
    (when (ref? type)
      (assert (not (nil? (deref type)))
              (str "defnode " where " requires a value type but was supplied with '"
                   original-form "' which cannot be used as a type")))
    (util/assert-form-kind "defnode" "registered value type"
                           (some-fn ref? value-type?) where type)
    {:value-type type
     :flags (if multivalued? #{:collection} #{})}))

(defmulti process-property-form first)

(defmethod process-property-form 'dynamic [[_ label forms]]
  (assert-symbol "dynamic" label)
  {:dynamics {(keyword label) {:fn forms}}})

(defmethod process-property-form 'value [[_ form]]
  {:value {:fn form}})

(defmethod process-property-form 'set [[_ form]]
  {:setter {:fn form}})

(defmethod process-property-form 'default [[_ form]]

  {:default {:fn form}})

(defmethod process-property-form 'validate [[_ form]]
  {:validate {:fn form}})

(defn process-property-forms
  [[type-form & body-forms]]
  (apply merge-with merge
         (parse-type-form "property" type-form)
         (for [b body-forms]
           (process-property-form b))))

(defmulti process-as first)

(defmethod process-as 'extern [[_ label & forms]]
  (assert-symbol "extern" label)
  (update-in
   (process-as (list* 'property label forms))
   [:property (keyword label) :flags] #(conj (or % #{}) :unjammable)))

(defmethod process-as 'property [[_ label & forms]]
  (assert-symbol "property" label)
  (let [klabel   (keyword label)
        propdef (cond-> (process-property-forms forms)
                  (contains? internal-keys klabel)
                  (update :flags #(conj (or % #{}) :internal)))
        outdef  (-> propdef
                    (dissoc :setter :dynamics :value)
                    (assoc :fn
                           (if-let [evaluator (-> propdef :value :fn)]
                             evaluator
                             `(dynamo.graph/fnk [~'this ~label] (get ~'this ~klabel)))))
        desc    {:property            {klabel propdef}
                 :property-order-decl (if (contains? internal-keys klabel) [] [klabel])
                 :output              {klabel outdef}}]
    desc))

(def ^:private output-flags   #{:cached :abstract})
(def ^:private output-options #{})

(defmethod process-as 'output [[_ label & forms]]
  (assert-symbol "output" label)
  (let [type-form                (first forms)
        base                     (parse-type-form "output" type-form)
        [flags options fn-forms] (parse-flags-and-options output-flags output-options (rest forms))
        abstract?                (contains? flags :abstract)]
    (assert (or abstract? (first fn-forms))
            (format "Output %s has no production function and is not abstract" label))
    (assert (not (next fn-forms))
            (format "Output %s seems to have something after the production function: " label (next fn-forms)))
    {:output
     {(keyword label)
      (merge-with into base {:flags   (into #{} (conj flags :explicit))
                             :options options
                             :fn      (if abstract?
                                        (abstract-function label type-form)
                                        (first fn-forms))})}}))

(def ^:private input-flags   #{:inject :array :cascade-delete})
(def ^:private input-options #{:substitute})

(defmethod process-as 'input [[_ label & forms]]
  (assert-symbol "input" label)
  (let [type-form         (first forms)
        base              (parse-type-form "input" type-form)
        [flags options _] (parse-flags-and-options input-flags input-options (rest forms))]
    {:input
     {(keyword label)
      (merge-with into base {:flags flags :options options})}}))

(defmethod process-as 'inherits [[_ & forms]]
  {:supertypes
   (set
    (for [f forms]
      (do
        (assert-symbol "inherits" f)
        (let [typeref (util/vgr f)]
          (assert (node-type-resolve typeref)
                  (str "Cannot inherit from " f " it cannot be resolved in this context (from namespace " *ns* ".)"))
          typeref))))})

(defmethod process-as 'display-order [[_ & decl]]
  {:display-order-decl (vec (first decl))})

(defn group-node-type-forms
  [forms]
  (let [parse (for [f forms :when (seq? f)] (process-as f))]
    (def parse* parse)
    (apply merge-with into parse)))

(defn node-type-merge
  ([] {})
  ([l] l)
  ([l r]
   {:property            (merge-with merge (:property l)     (:property r))
    :input               (merge-with merge (:input l)        (:input r))
    :output              (merge-with merge (:output l)       (:output r))
    :supertypes          (set (into (:supertypes l)          (:supertypes r)))
    ;; note special cases for property- and display-order-decl.
    ;; property-order-decl : new elements should be on the right
    :property-order-decl (vec (into (:property-order-decl r) (:property-order-decl l)))
    ;; display-order-decl : last one wins
    :display-order-decl  (or (:display-order-decl l) (:display-order-decl r))})
  ([l r & more]
   (reduce node-type-merge (node-type-merge l r) more)))

(defn merge-left
  [tree selector]
  (let [vals (map deref (get tree selector []))]
    (node-type-merge (apply node-type-merge vals) tree)))

(defn resolve-display-order
  [tree]
  (-> tree
      (assoc  :property-display-order
              (apply merge-display-order (:display-order-decl tree) (:property-order-decl tree)
                     (map (comp :property-display-order deref) (:supertypes tree))))
      (dissoc :property-order-decl :display-order-decl)))

(defn- wrap-when
  [tree key-pred val-pred xf]
  (walk/postwalk
   (fn [f]
     (if (vector? f)
       (let [[k v] f]
         (if (and (key-pred k) (val-pred v))
           [k (xf v)]
           f))
       f))
   tree))

(defn wrap-constant-fns
  [tree]
  (wrap-when tree #(= :fn %)
             (fn [v] (not (or (seq? v) (util/pfnksymbol? v) (util/pfnkvar? v))))
             (fn [v] `(dynamo.graph/fnk [] ~(if (symbol? v) (resolve v) v)))))

(defn extract-fn-arguments
  [tree]
  (walk/postwalk
   (fn [f]
     (if (and (map? f) (contains? f :fn))
       (assoc f :arguments (util/inputs-needed (:fn f)))
       f))
   tree))

(defn- prop+args [[pname pdef]]
  (into #{pname} (:arguments pdef)))

(defn attach-declared-properties
  [description]
  (let [publics (apply disj (reduce into #{} (map prop+args (:property description))) internal-keys)]
    (assoc-in description [:output :_declared-properties]
              {:value-type (->ValueTypeRef :dynamo.graph/Properties)
               :arguments  publics})))

(defn attach-declared-properties-behavior
  [description]
  (assoc-in description [:behavior :_declared-properties :fn]
            (declared-properties-function description)))

(defn merge-property-arguments
  [tree]
  (update tree :property
          #(util/map-vals
            (fn [prop]
              (let [allargs (-> #{}
                                (into (-> prop :arguments))
                                (into (->> prop :dynamics vals (mapcat :arguments)))
                                (into (->> prop :validate :arguments)))]
                (assoc prop :arguments allargs)))
            %)))

(defn process-node-type-forms
  [fqs forms]
  (-> (group-node-type-forms forms)
      (merge-left :supertypes)
      (assoc :name (str fqs))
      (assoc :key (keyword fqs))
      wrap-constant-fns
      resolve-display-order
      extract-fn-arguments
      merge-property-arguments
      attach-declared-properties
      attach-input-dependencies
      attach-output-behaviors
      attach-input-behaviors
      attach-property-behaviors
      attach-declared-properties-behavior
      verify-inputs-for-dynamics
      verify-inputs-for-outputs
      verify-labels))

(defn map-zipper [m]
  (zip/zipper
   (fn [x] (or (map? x) (map? (nth x 1))))
   (fn [x] (seq (if (map? x) x (nth x 1))))
   (fn [x children]
     (if (map? x)
       (into {} children)
       (assoc x 1 (into {} children))))
   m))

(defn key-path
  [z]
  (map first (rest (zip/path z))))

(defn extract-functions
  [tree]
  (loop [where (map-zipper tree)
         what  []]
    (if (zip/end? where)
      what
      (recur (zip/next where)
             (if (= :fn (first (zip/node where)))
               (conj what [(key-path where) (second (zip/node where))])
               what)))))

(defn dollar-name [prefix path]
    (->> path
         (map name)
         (interpose "$")
         (apply str prefix "$")
         symbol))

;;; ----------------------------------------
;;; Code generation

(defmacro gensyms
  [[:as syms] & forms]
  (let [bindings (vec (interleave syms (map (fn [s] `(gensym ~(name s))) syms)))]
    `(let ~bindings
       ~@forms)))

(declare fnk-argument-forms property-validation-exprs)

;; TODO - check if these receive a map or a ref.
(defn- desc-has-input?     [description argument] (contains? (:input description) argument))
(defn- desc-has-property?  [description argument] (contains? (:property description) argument))
(defn- desc-has-output?    [description argument] (contains? (:output description) argument))
(defn- desc-has-explicit-output?    [description argument]
  (contains? (get-in description [:output argument :flags]) :explicit))

(defn has-multivalued-input?  [description input-label]
  (contains? (get-in description [:input input-label :flags]) :array))

(defn has-singlevalued-input? [description input-label]
  (and (desc-has-input? description input-label)
       (not (has-multivalued-input? description input-label))))

(defn property-overloads-output? [description argument output]
  (and (= output argument)
       (desc-has-property? description argument)
       (desc-has-explicit-output? description argument)))

(defn unoverloaded-output? [description argument output]
  (and (not= output argument)
       (desc-has-output? description argument)))

(defn allow-nil [s]
  `(s/maybe ~s))

(defn allow-error [s]
  `(s/either ~s ErrorValue))

(defn relax-schema [s]
  (allow-error (allow-nil s)))

(defn deduce-argument-type
  "Return the type of the node's input label (or property). Take care
  with :array inputs."
  [description argument output]
  (cond
    (= :this argument)
    `s/Any

    (property-overloads-output? description argument output)
    (relax-schema (get-in description [:property argument :value-type]))

    (unoverloaded-output? description argument output)
    (relax-schema (get-in description [:output argument :value-type]))

    (desc-has-property? description argument)
    (relax-schema (get-in description [:property argument :value-type]))

    (has-multivalued-input? description argument)
    [(relax-schema (get-in description [:input argument :value-type]))]

    (has-singlevalued-input? description argument)
    (relax-schema (get-in description [:input argument :value-type]))

    (desc-has-output? description argument)
    (relax-schema (get-in description [:output argument :value-type]))))

(defn collect-argument-schema
  "Return a schema with the production function's input names mapped to the node's corresponding input type."
  [transform argument-schema description]
  (persistent!
   (reduce
    (fn [arguments desired-argument-name]
      (if (= s/Keyword desired-argument-name)
        arguments
        (let [argument-type (deduce-argument-type description desired-argument-name transform)]
          (assoc! arguments desired-argument-name (or argument-type s/Any)))))
    (transient {})
    argument-schema)))

(defn first-input-value-form
  [self-name ctx-name nodeid-sym input]
  `(let [[upstream-node-id# output-label#] (first (gt/sources (:basis ~ctx-name) ~nodeid-sym ~input))]
     (when-let [upstream-node# (and upstream-node-id# (gt/node-by-id-at (:basis ~ctx-name) upstream-node-id#))]
       (gt/produce-value upstream-node# output-label# ~ctx-name))))

(defn input-value-forms
  [self-name ctx-name nodeid-sym input]
  `(mapv (fn [[upstream-node-id# output-label#]]
           (let [upstream-node# (gt/node-by-id-at (:basis ~ctx-name) upstream-node-id#)]
             (gt/produce-value upstream-node# output-label# ~ctx-name)))
         (gt/sources (:basis ~ctx-name) (gt/node-id ~self-name) ~input)))

(defn maybe-use-substitute [description input forms]
  (if-let [sub (get-in description [:input input :options :substitute])]
   `(let [input# ~forms]
      (if (ie/error? input#)
        (util/apply-if-fn ~sub input#)
        input#))
    forms))

(defn call-with-error-checked-fnky-arguments
  [self-name ctx-name nodeid-sym label description arguments runtime-fnk-expr & [supplied-arguments]]
  (let [base-args      {:_node-id `(gt/node-id ~self-name) :basis `(:basis ~ctx-name)}
        arglist        (without arguments (keys supplied-arguments))
        argument-forms (zipmap arglist (map #(get base-args % (if (= label %)
                                                                `(gt/get-property ~self-name (:basis ~ctx-name) ~label)
                                                                (fnk-argument-forms self-name ctx-name nodeid-sym label description %)))
                                            arglist))
        argument-forms (merge argument-forms supplied-arguments)]
    `(let [arg-forms# ~argument-forms
           bad-errors# (ie/worse-than (:ignore-errors ~ctx-name) (flatten (vals arg-forms#)))]
       (if (empty? bad-errors#)
         (~runtime-fnk-expr arg-forms#)
         (assoc (ie/error-aggregate bad-errors#) :_node-id (gt/node-id ~self-name) :_label ~label)))))

(defn collect-base-property-value
  [self-name ctx-name nodeid-sym description prop-name]
  (let [property-definition (get-in description [:property prop-name])]
    (if (not (:value property-definition))
      `(gt/get-property ~self-name (:basis ~ctx-name) ~prop-name)
      (call-with-error-checked-fnky-arguments self-name ctx-name nodeid-sym prop-name description
                                              (:arguments (:value property-definition))
                                              `(let [nt# (deref (gt/node-type ~self-name (:basis ~ctx-name)))
                                                     fn# (get-in nt# [:property ~prop-name :value :fn])]
                                                 fn#)))))

(defn collect-property-value
  [self-name ctx-name nodeid-sym description prop]
  (let [property-definition (get-in description [:property prop])
        default?            (not (:value property-definition))
        validation          (:validation property-definition)
        get-expr            (if default?
                              `(gt/get-property ~self-name (:basis ~ctx-name) ~prop)
                              `(if (:in-transaction? ~ctx-name)
                                 (gt/get-property ~self-name (:basis ~ctx-name) ~prop)
                                 ~(call-with-error-checked-fnky-arguments self-name ctx-name nodeid-sym prop description
                                                                         (get-in property-definition [:value :arguments])
                                                                         `(-> ~self-name (gt/node-type (:basis ~ctx-name)) declared-properties ~prop :value :fn))))
        validate-expr       (property-validation-exprs self-name ctx-name description nodeid-sym prop)]
    (if validation
      `(let [v# ~get-expr]
         (if (:skip-validation ~ctx-name)
           v#
           (if (ie/error? v#)
             v#
             (let [valid-v# ~validate-expr]
               (if (nil? valid-v#) v# valid-v#)))))
      get-expr)))

(defn fnk-argument-forms
  [self-name ctx-name nodeid-sym output description argument]
  (cond
    (= :this argument)
    self-name

    (= :basis argument)
    `(:basis ~ctx-name)

    (property-overloads-output? description argument output)
    (collect-property-value self-name ctx-name nodeid-sym description argument)

    (unoverloaded-output? description argument output)
    `(gt/produce-value  ~self-name ~argument ~ctx-name)

    (desc-has-property? description argument)
    (if (= output argument)
      `(gt/get-property  ~self-name (:basis ~ctx-name) ~argument)
      (collect-property-value self-name ctx-name nodeid-sym description argument))

    (has-multivalued-input? description argument)
    (maybe-use-substitute
      description argument
      (input-value-forms self-name ctx-name nodeid-sym argument))

    (has-singlevalued-input? description argument)
    (maybe-use-substitute
     description argument
     (first-input-value-form self-name ctx-name nodeid-sym argument))

    (desc-has-output? description argument)
    `(gt/produce-value  ~self-name ~argument ~ctx-name)

    :else
    (assert false (str "A function needs an argument this node can't supply. There is no input, output, or property called " (pr-str argument)))))

(def ^:private jammable? (complement internal-keys))

(defn original-root [basis node-id]
  (let [node (gt/node-by-id-at basis node-id)
        orig-id (:original-id node)]
    (if orig-id
      (recur basis orig-id)
      node-id)))

(defn jam [self-name ctx-name nodeid-sym transform forms]
  (if (jammable? transform)
    `(let [basis# (:basis ~ctx-name)
           original# (if (:original-id ~self-name)
                       (gt/node-by-id-at basis# (original-root basis# ~nodeid-sym))
                       ~self-name)]
       (if-let [jammer# (get (:_output-jammers original#) ~transform)]
        (let [jam-value# (jammer#)]
          (if (ie/error? jam-value#)
            (assoc jam-value# :_label ~transform :_node-id ~nodeid-sym)
            jam-value#))
        ~forms))
    forms))

(defn property-has-default-getter?       [description label] (not (get-in description [:property label :value])))
(defn property-has-no-overriding-output? [description label] (not (desc-has-explicit-output? description label)))
(defn has-validation?                    [description label] (get-in description [:property label :validate]))

(defn apply-default-property-shortcut [self-name ctx-name property-name description forms]
  (let [property? (and (desc-has-property? description property-name) (property-has-no-overriding-output? description property-name))
        default?  (and (property-has-default-getter? description property-name)
                       (property-has-no-overriding-output? description property-name)
                       (not (has-validation? description property-name)))]
    (if default?
      `(gt/get-property ~self-name (:basis ~ctx-name) ~property-name)
      (if property?
        `(if (:in-transaction? ~ctx-name)
           (gt/get-property ~self-name (:basis ~ctx-name) ~property-name)
           ~forms)
        forms))))

(defn detect-cycles [ctx-name nodeid-sym transform description forms]
  `(do
     (assert (not (contains? (:in-production ~ctx-name) [~nodeid-sym ~transform]))
             (format "Cycle Detected on node type %s and output %s" ~(:name description) ~transform))
     ~forms))

(defn mark-in-production [ctx-name nodeid-sym transform forms]
  `(let [~ctx-name (update ~ctx-name :in-production conj [~nodeid-sym ~transform])]
     ~forms))

(defn check-caches [ctx-name nodeid-sym description transform forms]
  (if (get-in description [:output transform :flags :cached])
    `(let [local# @(:local ~ctx-name)
           global# (:snapshot ~ctx-name)
           key# [~nodeid-sym ~transform]]
       (cond
         (contains? local# key#) (get local# key#)
         (contains? global# key#) (if-some [cached# (get global# key#)]
                                           (do (swap! (:hits ~ctx-name) conj key#) cached#))
         true ~forms))
    forms))

(defn gather-inputs [input-sym schema-sym self-name ctx-name nodeid-sym description transform production-function forms]
  (let [arg-names       (get-in description [:output transform :arguments])
        argument-forms  (zipmap arg-names (map #(fnk-argument-forms self-name ctx-name nodeid-sym transform description %) arg-names))]
    (list `let
          [input-sym argument-forms]
          forms)))

(defn input-error-check [self-name ctx-name description label input-sym tail]
  (if (contains? internal-keys label)
    tail
    `(let [bad-errors# (ie/worse-than (:ignore-errors ~ctx-name) (flatten (vals ~input-sym)))]
       (if (empty? bad-errors#)
         (let [~input-sym (util/map-vals ie/use-original-value ~input-sym)]
           ~tail)
         (assoc (ie/error-aggregate bad-errors#) :_node-id (gt/node-id ~self-name) :_label ~label)))))

(defn call-production-function [self-name ctx-name description transform input-sym nodeid-sym output-sym forms]
  `(let [production-function# (-> ~self-name (gt/node-type (:basis ~ctx-name)) transforms ~transform :fn)
         ~input-sym           (assoc ~input-sym :_node-id ~nodeid-sym :basis (:basis ~ctx-name))
         ~output-sym          (production-function# ~input-sym)]
     ~forms))

(defn cache-output [ctx-name description transform nodeid-sym output-sym forms]
  `(do
     ~@(when (get-in description [:output transform :flags :cached])
         `[(swap! (:local ~ctx-name) assoc [~nodeid-sym ~transform] ~output-sym)])
     ~forms))

(defn deduce-output-type
  [self-name description transform]
  (let [schema (some-> (get-in description [:output transform :value-type]) value-type-schema)
        schema (if (get-in description [:output transform :flags :collection])
                 (vector schema)
                 schema)]
    (relax-schema schema)))

(defn schema-check-output [self-name ctx-name description transform nodeid-sym output-sym forms]
  `(let [output-schema# ~(deduce-output-type self-name description transform)]
     (if-let [validation-error# (s/check output-schema# ~output-sym)]
       (do
         (warn-output-schema ~nodeid-sym ~(:name description) ~transform ~output-sym output-schema# validation-error#)
         (throw (ex-info "SCHEMA-VALIDATION"
                         {:node-id          ~nodeid-sym
                          :type             ~(:name description)
                          :output           ~transform
                          :expected         output-schema#
                          :actual           ~output-sym
                          :validation-error validation-error#})))
       ~forms)))

(defn validate-output [self-name ctx-name description transform nodeid-sym output-sym forms]
  (if (and (desc-has-property? description transform)
           (property-has-no-overriding-output? description transform)
           (has-validation? description transform))
    (let [validate-expr (property-validation-exprs self-name ctx-name description nodeid-sym transform)]
      `(if (or (:skip-validation ~ctx-name) (ie/error? ~output-sym))
         ~forms
         (let [error# ~validate-expr
               bad-errors# (ie/worse-than (:ignore-errors ~ctx-name) (if error# [error#] []))]
           (if (empty? bad-errors#)
             ~forms
             (let [~output-sym (assoc (ie/error-aggregate bad-errors#) :_node-id (gt/node-id ~self-name) :_label ~transform)]
               ~forms)))))
    forms))

(defn node-output-value-function
  [description transform]
  (let [production-function (get-in description [:output transform :fn])]
    (gensyms [self-name ctx-name nodeid-sym input-sym schema-sym output-sym]
      `(fn [~self-name ~ctx-name]
         (let [~nodeid-sym (gt/node-id ~self-name)]
           ~(if (= transform :this)
              nodeid-sym
              (jam self-name ctx-name nodeid-sym transform
                (apply-default-property-shortcut self-name ctx-name transform description
                  (detect-cycles ctx-name nodeid-sym transform description
                    (mark-in-production ctx-name nodeid-sym transform
                      (check-caches ctx-name nodeid-sym description transform
                        (gather-inputs input-sym schema-sym self-name ctx-name nodeid-sym description transform production-function
                          (input-error-check self-name ctx-name description transform input-sym
                            (call-production-function self-name ctx-name description transform input-sym nodeid-sym output-sym
                              (schema-check-output self-name ctx-name description transform nodeid-sym output-sym
                                (validate-output self-name ctx-name description transform nodeid-sym output-sym
                                  (cache-output ctx-name description transform nodeid-sym output-sym
                                     output-sym)))))))))))))))))

(defn collect-property-values
  [self-name ctx-name beh-sym description nodeid-sym value-sym forms]
  (let [props (:property description)]
    `(let [~value-sym ~(apply merge
                              (for [[p _] (filter (comp external-property? val) props)]
                                {p `((get-in ~beh-sym [~p :fn]) ~self-name ~ctx-name)}))]
       ~forms)))

(defn- create-validate-argument-form
  [self-name ctx-name nodeid-sym description argument]
  ;; This is  similar to fnk-argument-forms, but simpler as we don't have to deal with the case where we're calling
  ;; an output function refering to an argument property with the same name.
  (cond
    (and (desc-has-property? description argument) (property-has-no-overriding-output? description argument))
    (collect-base-property-value self-name ctx-name nodeid-sym description argument)

    (desc-has-output? description argument)
    `(gt/produce-value  ~self-name ~argument ~ctx-name)

    (has-multivalued-input? description argument)
    (maybe-use-substitute
     description argument
     (input-value-forms self-name ctx-name nodeid-sym argument))

    (has-singlevalued-input? description argument)
    (maybe-use-substitute
     description argument
     (first-input-value-form self-name ctx-name nodeid-sym argument))

    (= :this argument)
    `~'this

    :else (assert false (str "unknown argument " argument " in call to validate function"))))

(defn property-validation-exprs
  [self-name ctx-name description nodeid-sym prop & [supplied-arguments]]
  (when (has-validation? description prop)
    (let [validator                   (get-in description [:property prop :validate])
          compile-time-validation-fnk (:fn validator)
          arglist                     (without (:arguments validator) (keys supplied-arguments))
          argument-forms              (zipmap arglist (map #(create-validate-argument-form self-name ctx-name nodeid-sym description % ) arglist))
          argument-forms              (merge argument-forms supplied-arguments)]
      `(let [arg-forms# ~argument-forms
             bad-errors# (ie/worse-than (:ignore-errors ~ctx-name) (flatten (vals arg-forms#)))]
         (if (empty? bad-errors#)
           ((-> ~self-name (gt/node-type (:basis ~ctx-name)) declared-properties ~prop :validate :fn) arg-forms#)
           (assoc (ie/error-aggregate bad-errors#) :_node-id (gt/node-id ~self-name) :_label ~prop))))))
;;; TODO: decorate with :production :validate?

(defn collect-validation-problems
  [self-name ctx-name nodeid-sym description value-map validation-map forms]
  (let [props-with-validation (util/map-vals :validate (:property description))
        validation-exprs      (partial property-validation-exprs self-name ctx-name description nodeid-sym)]
    `(let [~validation-map ~(apply hash-map
                                   (mapcat identity
                                           (for [[p validator] props-with-validation
                                                 :when validator]
                                             [p (validation-exprs p)])))]
       ~forms)))

(defn merge-problems
  [value-map validation-map]
  (let [validation-map (into {} (filter (comp not nil? second) validation-map))]
    (let [merger (fn [value problem]
                   (let [original-value (:value value)
                         problem (assoc problem :value original-value)]
                     (assoc value :validation-problems problem :value problem)))]
      (merge-with merger value-map validation-map))))

(defn merge-values-and-validation-problems
  [value-sym validation-sym forms]
  `(let [~value-sym (merge-problems ~value-sym ~validation-sym)]
     ~forms))

(defn collect-display-order
  [self-name ctx-name description display-order-sym forms]
  `(let [~display-order-sym ~(:property-display-order description)]
     ~forms))

(defn- assemble-properties-map
  [value-sym display-sym]
  `(hash-map :properties    ~value-sym
             :display-order ~display-sym))

(defn declared-properties-function
  [description]
  (let [validations? (not (empty? (keep :validate (vals (:property description)))))]
    (gensyms [self-name ctx-name beh-sym value-map validation-map nodeid-sym display-order]
       (if validations?
           `(fn [~self-name ~ctx-name]
              (let [~nodeid-sym    (gt/node-id ~self-name)
                    node-type-sym# (gt/node-type ~self-name (:basis ~ctx-name))
                    ~beh-sym       (-> node-type-sym# deref :property-behaviors)]
                ~(collect-property-values self-name ctx-name beh-sym description nodeid-sym value-map
                   (collect-validation-problems self-name ctx-name nodeid-sym description value-map validation-map
                     (merge-values-and-validation-problems value-map validation-map
                       (collect-display-order self-name ctx-name description display-order
                         (assemble-properties-map value-map display-order)))))))
           `(fn [~self-name ~ctx-name]
              (let [~nodeid-sym    (gt/node-id ~self-name)
                    node-type-sym# (gt/node-type ~self-name (:basis ~ctx-name))
                    ~beh-sym       (-> node-type-sym# deref :property-behaviors)]
                ~(collect-property-values self-name ctx-name beh-sym description nodeid-sym value-map
                   (collect-display-order self-name ctx-name description display-order
                     (assemble-properties-map value-map display-order)))))))))

(defn node-input-value-function
  [description input]
  (gensyms [self-name ctx-name nodeid-sym]
     `(fn [~self-name ~ctx-name]
        (let [~nodeid-sym (gt/node-id ~self-name)]
          ~(maybe-use-substitute
            description input
            (cond
              (has-multivalued-input? description input)
              (input-value-forms self-name ctx-name nodeid-sym input)

              (has-singlevalued-input? description input)
              (first-input-value-form self-name ctx-name nodeid-sym input)))))))

(defn property-dynamics
  [self-name ctx-name nodeid-sym description property-name property-type value-form]
  (apply merge
         (for [[dynamic-label {:keys [fn arguments] :as dynamic}] (get property-type :dynamics)]
           {dynamic-label (call-with-error-checked-fnky-arguments self-name ctx-name nodeid-sym dynamic-label description arguments fn)})))

(defn property-value-exprs
  [self-name ctx-name nodeid-sym description prop-name prop-type]
  (let [basic-val `{:type    ~(:value-type prop-type)
                    :value   ~(collect-base-property-value self-name ctx-name nodeid-sym description prop-name)
                    :node-id ~nodeid-sym}]
    (if (not (empty? (:dynamics prop-type)))
      (let [dyn-exprs (property-dynamics self-name ctx-name nodeid-sym description prop-name prop-type basic-val)]
        (merge basic-val dyn-exprs))
      basic-val)))

(defn property-accessor-value-function
  [description property]
  (gensyms [self-name ctx-name nodeid-sym]
    `(fn [~self-name ~ctx-name]
        (let [~nodeid-sym (gt/node-id ~self-name)]
          ~(property-value-exprs self-name ctx-name nodeid-sym description property (get-in description [:property property]))))))



;;; ----------------------------------------
;;; Overrides

(defrecord OverrideNode [override-id node-id original-id properties]
  gt/Node
  (node-id             [this]                      node-id)
  (node-type           [this basis]                (gt/node-type (gt/node-by-id-at basis original-id) basis))
  (get-property        [this basis property]
    (get properties property (gt/get-property (gt/node-by-id-at basis original-id) basis property)))
  (set-property        [this basis property value]
    (if (= :_output-jammers property)
      (throw (ex-info "Not possible to mark override nodes as defective" {}))
      (assoc-in this [:properties property] value)))

  gt/Evaluation
  (produce-value       [this output evaluation-context]
    (let [basis    (:basis evaluation-context)
          original (gt/node-by-id-at basis original-id)
          type     (gt/node-type this basis)]
      (when *node-value-debug*
        (println (nodevalstr this type output " (override node)")))
      (binding [*node-value-nesting* (inc *node-value-nesting*)]
        (cond
          (= :_node-id output)
          node-id

          (or (= :_declared-properties output)
              (= :_properties output))
          (let [beh           (behavior type output)
                props         ((:fn beh) this evaluation-context)
                orig-props    (:properties ((:fn beh) original evaluation-context))
                dynamic-props (without (set (concat (keys properties) (keys orig-props))) (set (keys (public-properties type))))
                props         (reduce-kv (fn [p k v]
                                           (if (and (dynamic-props k)
                                                    (= original-id (:node-id v)))
                                             (cond-> p
                                               (contains? v :original-value)
                                               (assoc-in [:properties k :value] (:value v)))
                                             p))
                                         props orig-props)]
            (reduce (fn [props [k v]]
                      (cond-> props
                        (and (= :_properties output)
                             (dynamic-props k))
                        (assoc-in [:properties k :value] v)

                        (contains? orig-props k)
                        (assoc-in [:properties k :original-value]
                                  (get-in orig-props [k :value]))))
                    props properties))

          (or (has-output? type output)
              (has-input? type output))
          (let [beh (behavior type output)]
            ((:fn beh) this evaluation-context))

          true
          (let [dyn-properties (node-value* original :_properties evaluation-context)]
            (if (contains? (:properties dyn-properties) output)
              (get properties output)
              (node-value* original output evaluation-context)))))))

  gt/OverrideNode
  (clear-property [this basis property] (update this :properties dissoc property))
  (override-id    [this]                override-id)
  (original       [this]                original-id)
  (set-original   [this original-id]    (assoc this :original-id original-id)))

(defn make-override-node [override-id node-id original-id properties]
  (->OverrideNode override-id node-id original-id properties))
