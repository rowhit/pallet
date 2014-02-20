(ns pallet.core.group
  "Pallet Group functions for adjusting nodes

Provides the node-spec, service-spec and group-spec abstractions.

Provides the lift and converge operations.

Uses a TargetMap to describe a node with its group-spec info."
  (:require
   [clojure.core.async :as async :refer [<! <!! alts!! chan close! timeout]]
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> loop>
            inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.set :refer [union]]
   [clojure.string :as string :refer [blank?]]
   [clojure.tools.logging :as logging :refer [debugf tracef]]
   [pallet.async
    :refer [concat-chans exec-operation from-chan go-logged go-try
            reduce-results]]
   [pallet.compute
    :refer [node-spec create-nodes destroy-nodes nodes service-properties]]
   [pallet.contracts
    :refer [check-converge-options
            check-group-spec
            check-lift-options]]
   [pallet.core.api :as api :refer [errors]]
   [pallet.core.executor.ssh :as ssh]
   [pallet.core.phase :as phase :refer [phases-with-meta process-phases]]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.core.session :as session
    :refer [base-session? extension plan-state
            recorder target target-session? update-extension]]
   [pallet.core.spec
    :refer [create-targets default-phase-meta destroy-targets extend-specs
            lift-op* lift-phase merge-spec-algorithm merge-specs
            os-detection-phases target-id-map targets]]
   [pallet.core.user :as user]
   [pallet.environment :refer [merge-environments]]
   [pallet.node :as node :refer [node?]]
   [pallet.thread-expr :refer [when->]]
   [pallet.utils
    :refer [combine-exceptions maybe-update-in total-order-merge]]))

;;; # Domain Model

;;; ## Group-spec

;;; TODO add :removal-selection-fn
(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to its node-spec

   - :extends        specify a server-spec, a group-spec, or sequence thereof
                     and is used to inherit phases, etc.

   - :phases         used to define phases. Standard phases are:
   - :phases-meta    metadata to add to the phases
   - :default-phases a sequence specifying the default phases
   - :bootstrap      run on first boot of a new node
   - :configure      defines the configuration of the node.

   - :count          specify the target number of nodes for this node-spec
   - :packager       override the choice of packager to use
   - :node-spec      default node-spec for this group-spec
   - :node-filter    a predicate that tests if a node is a member of this
                     group."
  ;; Note that the node-filter is not set here for the default group-name based
  ;; membership, so that it does not need to be updated by functions that modify
  ;; a group's group-name.
  [name
   & {:keys [extends count image phases phases-meta default-phases packager
             node-spec roles node-filter]
      :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (let [group-name (keyword (clojure.core/name name))]
    (check-group-spec
     (->
      (merge options)
      (when-> roles
              (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
      (extend-specs extends)
      (maybe-update-in
       [:phases] phases-with-meta phases-meta default-phase-meta)
      (update-in [:default-phases] #(or default-phases % [:configure]))
      (dissoc :extends :phases-meta)
      (assoc :group-name group-name)
      (vary-meta assoc :type ::group-spec)))))

;;; ## Cluster Spec
(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn expand-group-spec-with-counts
  "Expand a converge node spec into its groups"
  ([node-set spec-count]
     (letfn [(*1 [x y] (* (or x 1) y))
             (scale-spec [spec factor]
               (update-in spec [:count] *1 factor))
             (set-spec [node-spec]
               (mapcat
                (fn [[node-spec spec-count]]
                  (if-let [groups (:groups node-spec)]
                    (expand-group-spec-with-counts groups spec-count)
                    [(assoc node-spec :count spec-count)]))
                node-set))]
       (cond
        (sequential? node-set) (mapcat
                                #(expand-group-spec-with-counts % spec-count)
                                node-set)
        (map? node-set) (if-let [groups (:groups node-set)]
                          (let [spec (scale-spec node-set spec-count)]
                            (mapcat
                             #(expand-group-spec-with-counts % (:count spec))
                             groups))
                          (if (:group-name node-set)
                            [(scale-spec node-set spec-count)]
                            (set-spec node-spec)))
        :else [(scale-spec node-set spec-count)])))
  ([node-set] (expand-group-spec-with-counts node-set 1)))

(defn cluster-spec
  "Create a cluster-spec.

   `name` is used as a prefix for all groups in the cluster.

   - :groups    specify a sequence of groups that define the cluster

   - :extends   specify a server-spec, a group-spec, or sequence thereof
                for all groups in the cluster

   - :phases    define phases on all groups.

   - :node-spec default node-spec for the nodes in the cluster

   - :roles     roles for all group-specs in the cluster"
  [cluster-name
   & {:keys [extends groups phases node-spec roles] :as options}]
  (let [cluster-name (name cluster-name)
        group-prefix (if (blank? cluster-name) "" (str cluster-name "-"))]
    (->
     options
     (update-in [:groups]
                (fn [group-specs]
                  (map
                   (fn [group-spec]
                     (->
                      node-spec
                      (merge (dissoc group-spec :phases))
                      (update-in
                       [:group-name]
                       #(keyword (str group-prefix (name %))))
                      (update-in [:roles] union roles)
                      (extend-specs extends)
                      (extend-specs [{:phases phases}])
                      (extend-specs [(select-keys group-spec [:phases])])))
                   (expand-group-spec-with-counts group-specs 1))))
     (dissoc :extends :node-spec)
     (assoc :cluster-name (keyword cluster-name))
     (vary-meta assoc :type ::cluster-spec))))


;;; # Plan-state scopes
(ann target-scopes [TargetMap -> ScopeMap])
(defn target-scopes
  [target]
  (merge {:group (:group-name target)
          :universe true}
         (if-let [node (:node target)]
           {:host (node/id node)
            :service (node/compute-service node)
            :provider (:provider
                       (service-properties
                        (node/compute-service node)))})))

(ann admin-user [Session -> User])
(defn admin-user
  "User that remote commands are run under."
  [session]
  {:post [(user/user? %)]}
  ;; Note: this is not (-> session :execution-state :user), which is
  ;; set to the actual user used for authentication when executing
  ;; scripts, and may be different, e.g. when bootstrapping.
  (or (if (:target session)
        (let [m (plan-state/merge-scopes
                 (plan-state/get-scopes
                  (:plan-state session)
                  (target-scopes (target session))
                  [:user]))]
          (and (not (empty? m)) m)))
      (-> session :execution-state :user)))


;; (ann group-name [Session -> GroupName])
;; (defn group-name
;;   "Group name of the target-node."
;;   [session]
;;   {:pre [(target-session? session)]}
;;   (-> session :target :group-name))

;;; # Target Extension Functions

(ann nodes-in-group [Session GroupName -> TargetMapSeq])
(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  [session group-name]
  (->>
   (targets session)
   (filter
    (fn> [t :- TargetMap]
         (or (= (:group-name t) group-name)
             (when-let [group-names (:group-names t)]
               (get group-names group-name)))))))

(ann ^:no-check groups-with-role [BaseSession -> (Seqable GroupSpec)])
(defn groups-with-role
  "All target groups with the specified role."
  [session role]
  (->>
   (targets session)
   (filter (fn> [t :- TargetMap] ((:roles t #{}) role)))
   (map (fn> [t :- TargetMap] (dissoc t :node)))
   ((fn> [x :- TargetMapSeq] ((inst distinct TargetMap) x)))))

;; (defn groups-with-role
;;   "All target groups with the specified role."
;;   [session role]
;;   (->>
;;    @(:system-targets session)
;;    (filter #((:roles % #{}) role))
;;    (map #(dissoc % :node))
;;    distinct))

(ann ^:no-check nodes-with-role [BaseSession -> TargetMapSeq])
(defn nodes-with-role
  "All target nodes with the specified role."
  [session role]
  (->> (targets session)
       (filter
        (fn> [node :- TargetMap]
          (when-let [roles (:roles node)]
            (roles role))))))

(ann role->nodes-map [BaseSession -> (Map Keyword (Seqable Node))])
(defn role->nodes-map
  "Returns a map from role to nodes."
  [session]
  (reduce
   (fn> [m :- (Map Keyword (Seqable Node))
         node :- TargetMap]
        (reduce (fn> [m :- (Map Keyword (Seqable Node))
                      role :- Keyword]
                     (update-in m [role] conj node))
                m
                (:roles node)))
   {}
   (targets session)))

;; (defn target-group-name
;;   "Return the group name for node, as recorded in the session-targets."
;;   [session node]
;;   (:group-name (session-target session node)))


;;; # Group Name Functions

;;; We tag nodes with the group-name if possible, else fall back on
;;; relying on the encoding of the group name into the node name.

(def group-name-tag
  "The name of the tag used to record the group name on the node."
  "/pallet/group-name")

(ann node-has-group-name? [Node GroupName -> boolean])
(defn node-has-group-name?
  "Return a predicate to check if a node has the specified group name.
  If the node is taggable, we check the group-name-tag, otherwise we
  fall back onto checking the whether the node's base-name matches the
  group name."
  [node group-name]
  (if (node/taggable? node)
    (= group-name (node/tag node group-name-tag))
    (node/has-base-name? node group-name)))

(ann node-in-group? [Node GroupSpec -> boolean])
(defn node-in-group?
  "Check if a node satisfies a group's node-filter."
  {:internal true}
  [node group]
  {:pre [(check-group-spec group)]}
  (debugf "node-in-group? node %s group %s" node group)
  (debugf "node-in-group? node in group %s" (node-has-group-name? node (name (:group-name group))))
  ((:node-filter group #(node-has-group-name? % (name (:group-name group))))
   node))

;;; # Map Nodes to Groups Based on Group's Node-Filter
;; TODO remove :no-check
(ann ^:no-check node->target-map [(Nilable (NonEmptySeqable GroupSpec)) Node
                                 -> IncompleteGroupTargetMap])
(defn node->target-map
  "Build a map entry from a node and a list of groups."
  {:internal true}
  [groups node]
  {:pre [(every? #(check-group-spec %) groups)]}
  (when-let [groups (seq (->>
                          groups
                          (filter (fn> [group :- GroupSpec]
                                    (node-in-group? node group)))
                          (map (fn> [group :- GroupSpec]
                                 (check-group-spec group)
                                 (assoc-in
                                  group [:group-names]
                                  (set [(:group-name group)]))))))]
    (debugf "node->target-map groups %s node %s" (vec groups) node)
    (reduce
     (fn> [target :- GroupSpec group :- GroupSpec]
       (merge-specs merge-spec-algorithm target group))
     {:node node}
     groups)))

(ann service-state [(NonEmptySeqable Node) (Nilable (NonEmptySeqable GroupSpec))
                    -> IncompleteGroupTargetMapSeq])
(defn service-state
  "For a sequence of nodes, filter those nodes in the specified
  `groups`. Returns a sequence that contains a target-map for each
  matching node."
  [nodes groups]
  {:pre [(every? #(check-group-spec %) groups)
         (every? node? nodes)]}
  (let [_ (tracef "service-state %s" (vec nodes))
        targets (seq (remove nil? (map #(node->target-map groups %) nodes)))]
    (debugf "service-state targets %s" (vec targets))
    targets))

;; (ann ^:no-check service-groups [ComputeService -> (Seqable GroupSpec)])
;; (defn service-groups
;;   "Query the available nodes in a `compute-service`, returning a group-spec
;;   for each group found."
;;   [compute-service]
;;   (->> (nodes compute-service)
;;        (remove terminated?)
;;        (map group-name)
;;        (map (fn> [gn :- GroupName] {:group-name gn}))
;;        (map (fn> [m :- (HMap :mandatory {:group-name GroupName})]
;;               ((inst vary-meta
;;                      (HMap :mandatory {:group-name GroupName})
;;                      Keyword Keyword)
;;                m assoc :type :pallet.api/group-spec)))))

;;; # Operations


(def ^{:doc "node-specific environment keys"}
  node-keys [:image :phases])

(defn group-with-environment
  "Add the environment to a group."
  [environment group]
  (merge-environments
   (maybe-update-in (select-keys environment node-keys)
                    [:phases] phases-with-meta {} default-phase-meta)
   group
   (maybe-update-in (get-in environment [:groups group])
                    [:phases] phases-with-meta {} default-phase-meta)))

;;; ## Calculation of node count adjustments
(def-alias GroupDeltaMap (HMap :mandatory {:actual AnyInteger
                                          :target AnyInteger
                                          :delta AnyInteger
                                          :group GroupSpec}))
(def-alias GroupDeltaSeq (Seqable GroupDeltaMap))

(ann group-delta [TargetMapSeq GroupSpec -> GroupDeltaMap])
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
  (debugf "group-delta targets %s group %s" (vec targets) group)
  (let [existing-count (count targets)
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw
       (ex-info
        (format "Node :count not specified for group: %s" group)
        {:reason :target-count-not-specified
         :group group}) (:group-name group)))
    {:actual existing-count
     :target target-count
     :targets targets
     :delta (- target-count existing-count)
     :group group}))

(ann group-deltas [TargetMapSeq (Nilable (Seqable GroupSpec)) -> GroupDeltaSeq])
(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  (debugf "group-deltas targets %s groups %s" (vec targets) (vec groups))
  (map
   (fn [group]
     (group-delta (filter
                   (fn [t]
                     (node-in-group? (:node t) group))
                   targets)
                  group))
   groups))

;;; ### Nodes and Groups to Remove
;; TODO remove no-check when core.typed can handle first, second on Vector*
(def-alias GroupNodesForRemoval
  (HMap :mandatory
        {:targets (NonEmptySeqable TargetMap)
         :group GroupSpec
         :remove-group boolean}))

(ann group-removal-spec
  [GroupDelta -> '[GroupSpec (HMap :mandatory
                                   {:targets (NonEmptySeqable TargetMap)
                                    :all boolean})]])
(defn group-removal-spec
  "Return a map describing the group and targets to be removed."
  [{:keys [group target targets delta]}]
  (let [f (:removal-selection-fn group take)]
    {:group group
     :targets (f (- delta)
                 (filter
                  (fn> [target :- TargetMap]
                    (node-in-group? (:node target) group))
                  targets))
     :remove-group (zero? target)}))

(ann ^:no-check group-removal-specs
     [TargetMapSeq GroupDeltaSeq
      -> (Seqable '[GroupSpec GroupNodesForRemoval])])
(defn group-removal-specs
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [group-deltas]
  (->>
   group-deltas
   (filter (comp neg? :delta))
   (map group-removal-spec)))

;;; ### Nodes and Groups to Add

(defn group-add-spec
  [{:keys [group delta actual] :as group-delta}]
  {:count delta
   :group group
   :create-group (and (zero? actual) (pos? delta))})

(defn group-add-specs
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [group-deltas]
  (->>
   group-deltas
   (filter (comp pos? :delta))
   (map group-add-spec)))


;; ;; TODO remove no-check when core.typed can handle first, second on Vector*
;; (ann ^:no-check groups-to-create [GroupDeltaSeq -> (Seq GroupSpec)])
;; (defn groups-to-create
;;   "Return a sequence of groups that currently have no nodes, but will have nodes
;;   added."
;;   [group-deltas]
;;   (letfn> [new-group? :- [GroupDeltaMap -> boolean]
;;            ;; TODO revert to destructuring when supported by core.typed
;;            (new-group? [delta]
;;             (and (zero? (get delta :actual)) (pos? (get delta :target))))]
;;     (->>
;;      group-deltas
;;      (filter (fn> [delta :- GroupDelta]
;;                   (new-group? ((inst second GroupDeltaMap) delta))))
;;      ((inst map GroupSpec GroupDelta) (inst first GroupSpec))
;;      ((inst map GroupSpec GroupSpec)
;;       (fn> [group-spec :- GroupSpec]
;;            (assoc group-spec :target-type :group))))))

;; ;; TODO remove no-check when core.typed can handle first, second on Vector*
;; (ann ^:no-check nodes-to-add
;;      [GroupDeltaSeq -> (Seqable '[GroupSpec AnyInteger])])
;; (defn nodes-to-add
;;   "Finds the specified number of nodes to be added to the given groups.
;;   Returns a map from group to a count of servers to add"
;;   [group-deltas]
;;   (->>
;;    group-deltas
;;    (filter (fn> [group-delta :- GroupDelta]
;;                 (when (pos? (get (second group-delta) :delta))
;;                   [(first group-delta)
;;                    (get (second group-delta) :delta)])))))



;;; ## Node creation and removal
;; TODO remove :no-check when core.type understands assoc



(ann remove-nodes [Session ComputeService GroupSpec GroupNodesForRemoval
                   -> nil])
(defn remove-nodes
  "Removes `targets` from `group`. If `:remove-group` is true, then
  all nodes for the group are being removed, and the group should be
  removed.  Puts a result onto the output channel, ch, as a rex-tuple
  where the value is a map with :destroy-servers, :old-node-ids, and
  destroy-groups keys."
  [session compute-service group remove-group targets ch]
  (go-try ch
    (let [c (chan 1)]
      (destroy-targets session compute-service targets c)
      (let [[{:keys [destroy-server old-node-ids] :as m} e
             :as phase-res] (<! c)]
        (debugf "remove-nodes %s %s %s %s"
                remove-group
                (count destroy-server)
                (count old-node-ids)
                (count targets))
        (if (and remove-group (= (count destroy-server)
                                 (count old-node-ids)
                                 (count targets)))
          (do
            (lift-phase
             session :destroy-group [(assoc group :target-type :group)] nil c)
            (let [[gr ge :as group-res] (<! c)]
              (>! ch [(assoc m :destroy-group gr)
                      (combine-exceptions (filter identity [e ge]))])))
          (>! ch phase-res))))))


(ann remove-group-nodes
  [Session ComputeService (Seqable '[GroupSpec GroupNodesForRemoval]) -> Any])
(defn remove-group-nodes
  "Removes targets from groups accordingt to the removal-specs
  sequence.  The result is written to the channel, ch.
  Each removal-spec is a map with :group, :remove-group and :targets keys."
  [session compute-service removal-specs ch]
  (debugf "remove-group-nodes %s" (seq removal-specs))
  (go-try ch
    (let [c (chan)]
      (doseq [{:keys [group remove-group targets]} removal-specs]
        (remove-nodes session compute-service group remove-group targets c))
      (loop [n (count removal-specs)
             res []
             exceptions []]
        (debugf "remove-group-nodes res %s" res)
        (if-let [[r e] (and (pos? n) (<! c))]
          (recur (dec n) (conj res r) (if e (conj exceptions e) exceptions))
          (>! ch [res (combine-exceptions exceptions)]))))))


(ann ^:no-check add-nodes
     [Session ComputeService GroupSpec AnyInteger -> (Seq TargetMap)])
(defn add-nodes
  "Create `count` nodes for a `group`."
  [session compute-service group count create-group ch]
  {:pre [(base-session? session)]}
  (debugf "add-nodes %s %s" group count)
  (go-try ch
    (let [c (chan)
          [result e] (if create-group
                       (do
                         (lift-phase
                          session :create-group
                          [(assoc group :target-type :group)] nil c)
                         (<! c)))]
      (if e
        (>! ch [{:create-group result} e])
        (do
          (create-targets
           session
           compute-service
           group
           (admin-user session)
           count
           {:node-name (name (:group-name group))}
           c)
          (let [[{:keys [bootstrap targets] :as m} e2] (<! c)]
            (>! ch [(merge m {:create-group result})
                    e2])))))))


(ann add-group-nodes
  [Session ComputeService (Seqable '[GroupSpec GroupDeltaMap]) ->
   (Seqable (ReadOnlyPort '[(Nilable (Seqable TargetMap))
                            (Nilable (ErrorMap '[GroupSpec GroupDeltaMap]))]))])
(defn add-group-nodes
  "Create nodes for groups."
  [session compute-service group-add-specs ch]
  {:pre [(base-session? session)]}
  (debugf "add-group-nodes %s %s" compute-service (vec group-add-specs))
  (go-try ch
    ;; We use a buffered channel so as not blocked when we read the
    ;; channel returned by add-group-nodes
    (let [c (chan (count group-add-specs))]
      (doseq [{:keys [group count create-group]} group-add-specs]
        (add-nodes session compute-service group count create-group c))
      (loop [n (count group-add-specs)
             res []
             exceptions []]
        (debugf "add-group-nodes res %s" res)
        (if-let [[r e] (and (pos? n) (<! c))]
          (recur (dec n) (conj res r) (if e (conj exceptions e) exceptions))
          (>! ch [res (combine-exceptions exceptions)]))))))

;;; # Execution helpers

;;; Node count adjuster
(defn node-count-adjuster
  "Adjusts node counts. Groups are expected to have node counts on them.
Return a map.  The :new-targets key will contain a sequence of new
targets; the :old-node-ids a sequence of removed node-ids,
and :results a sequence of phase results from
the :destroy-server, :destroy-group, and :create-group phases."
  [session compute-service groups targets ch]
  {:pre [(base-session? session)
         compute-service
         (every? :count groups)
         (every? (some-fn :node :group) targets)]}
  (debugf "node-count-adjuster targets %s" (vec targets))
  (go-try ch
    (let [group-deltas (group-deltas targets groups)
          removal-specs (group-removal-specs group-deltas)
          add-specs (group-add-specs group-deltas)

          targets-map (reduce #(assoc %1 (node/id (:node %2)) %2)
                              {} targets)
          c-remove (chan)
          c-add (chan)]
      (debugf "node-count-adjuster group-deltas %s" (vec group-deltas))
      (debugf "node-count-adjuster removal-specs %s" (vec removal-specs))

      (remove-group-nodes session compute-service removal-specs c-remove)
      (add-group-nodes session compute-service add-specs c-add)

      (let [[res-remove e-remove] (<! c-remove)
            [res-add e-add] (<! c-add)
            old-node-ids (mapcat :old-node-ids res-remove)
            targets-map (apply dissoc targets-map old-node-ids)
            targets (concat (vals targets-map) (mapcat :targets res-add))
            result {:results (concat (mapcat :destroy-server res-remove)
                                     (mapcat :destroy-group res-remove)
                                     (mapcat :results res-add))
                    :targets targets
                    :old-node-ids old-node-ids}]
        (debugf "node-count-adjuster res-remove %s" (vec res-remove))
        (debugf "node-count-adjuster result %s" result)
        (>! ch [result (combine-exceptions [e-remove e-add])])))))

;;; ## Operations
;;;

(defmethod target-id-map :group [target]
  (select-keys target [:group-name]))

(defn- groups-with-phases
  "Adds the phases from phase-map into each group in the sequence `groups`."
  [groups phase-map]
  (letfn [(add-phases [group]
            (update-in group [:phases] merge phase-map))]
    (map add-phases groups)))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn split-groups-and-targets
  "Split a node-set into groups and targets. Returns a map with
:groups and :targets keys"
  [node-set]
  (logging/tracef "split-groups-and-targets %s" (vec node-set))
  (->
   (group-by
    #(if (and (map? %)
              (every? map? (keys %))
              (every?
               (fn node-or-nodes? [x] (or (node? x) (sequential? x)))
               (vals %)))
       :targets
       :groups)
    node-set)
   (update-in
    [:targets]
    #(mapcat
      (fn [m]
        (reduce
         (fn [result [group nodes]]
           (if (sequential? nodes)
             (concat result (map (partial assoc group :node) nodes))
             (conj result (assoc group :node nodes))))
         []
         m))
      %))))

(defn all-group-nodes
  "Returns the service state for the specified groups"
  [compute groups all-node-set]
  (service-state
   (nodes compute)
   (concat groups (map
                   (fn [g] (update-in g [:phases] select-keys [:settings]))
                   all-node-set))))

(def ^{:doc "Arguments that are forwarded to be part of the environment"}
  environment-args [:compute :blobstore :provider-options])

(defn group-node-maps
  "Return the nodes for the specified groups."
  [compute groups & {:keys [async timeout-ms timeout-val] :as options}]
  (all-group-nodes compute groups nil) options)

(def ^{:doc "A sequence of keywords, listing the lift-options"}
  lift-options
  [:targets :phase-execution-f :execution-settings-f
   :post-phase-f :post-phase-fsm :partition-f])

(defn converge*
  "Converge the existing compute resources with the counts
   specified in `group-spec->count`.  Options are as for `converge`.
   The result is written to the channel, ch."
  [group-spec->count ch {:keys [compute blobstore user phase
                                all-nodes all-node-set environment
                                plan-state debug os-detect]
                         :or {os-detect true}
                         :as options}]
  (check-converge-options options)
  (logging/tracef "environment %s" environment)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (merge phase-map (os-detection-phases))
                    phase-map)
        _ (debugf "phase-map %s" phase-map)
        groups (if (map? group-spec->count)
                 [group-spec->count]
                 group-spec->count)
        groups (expand-group-spec-with-counts group-spec->count)
        {:keys [groups targets]} (-> groups
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        lift-options (select-keys options lift-options)
        initial-plan-state (or plan-state {})
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        phases (or (seq phases)
                   (apply total-order-merge
                          (map
                           #(get % :default-phases [:configure])
                           (concat groups targets))))]
    (debugf "converge* targets %s" (vec targets))
    (doseq [group groups] (check-group-spec group))
    (go-try ch
      (>! ch
          (let [session (session/create
                         {:executor (ssh/ssh-executor)
                          :plan-state (in-memory-plan-state initial-plan-state)
                          :user user/*admin-user*})
                nodes-set (all-group-nodes compute groups all-node-set)
                nodes-set (concat nodes-set targets)
                _  (debugf "nodes-set before converge %s" (vec nodes-set))
                _ (when-not (or compute (seq nodes-set))
                    (throw (ex-info
                            "No source of nodes"
                            {:error :no-nodes-and-no-compute-service})))
                c (chan)
                _ (node-count-adjuster session compute groups nodes-set c)
                [converge-result e] (<! c)]
            (debugf "new targets %s" (vec (:targets converge-result)))
            (debugf "old ids %s" (vec (:old-node-ids converge-result)))
            (if e
              [converge-result e]
              (let [nodes-set (:targets converge-result)
                    _ (debugf "nodes-set after converge %s" (vec nodes-set))
                    phases (concat (when os-detect [:pallet/os-bs :pallet/os])
                                   [:settings :bootstrap] phases)]
                (debugf "running phases %s" (vec phases))
                (lift-op* session phases nodes-set lift-options c)
                (let [[result e] (<! c)]
                  [(-> converge-result
                       (update-in [:results] concat result))
                   e]))))))))

(defn converge
  "Converge the existing compute resources with the counts specified in
`group-spec->count`. New nodes are started, or nodes are destroyed to obtain the
specified node counts.

`group-spec->count` can be a map from group-spec to node count, or can be a
sequence of group-specs containing a :count key.

This applies the `:bootstrap` phase to all new nodes and, by default,
the :configure phase to all running nodes whose group-name matches a key in the
node map.  Phases can also be specified with the `:phase` option, and will be
applied to all matching nodes.  The :configure phase is the default phase
applied.

## Options

`:compute`
: a compute service.

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [group-spec->count & {:keys [compute blobstore user phase
                               all-nodes all-node-set environment
                               async timeout-ms timeout-val
                               debug plan-state]
                        :as options}]
  ;; TODO  (load-plugins)
  (let [ch (chan)]
    (converge* group-spec->count ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

(defn lift*
  "Asynchronously execute a lift of phases on the node-set.  Options
  as specified in `lift`."
  [node-set ch {:keys [compute phase all-node-set environment debug plan-state
                       os-detect]
                :or {os-detect true}
                :as options}]
  (logging/trace "Lift*")
  (check-lift-options options)
  (let [[phases phase-map] (process-phases phase)
        phase-map (if os-detect
                    (merge phase-map (os-detection-phases))
                    phase-map)
        {:keys [groups targets]} (-> node-set
                                     expand-cluster-groups
                                     split-groups-and-targets)
        _ (logging/tracef "groups %s" (vec groups))
        _ (logging/tracef "targets %s" (vec targets))
        _ (logging/tracef "environment keys %s"
                          (select-keys options environment-args))
        _ (logging/tracef "options %s" options)
        environment (merge-environments
                     (and compute (pallet.environment/environment compute))
                     environment
                     (select-keys options environment-args))
        groups (groups-with-phases groups phase-map)
        targets (groups-with-phases targets phase-map)
        groups (map (partial group-with-environment environment) groups)
        targets (map (partial group-with-environment environment) targets)
        initial-plan-state (or plan-state {})
        ;; TODO
        ;; initial-plan-state (assoc (or plan-state {})
        ;;                      action-options-key
        ;;                      (select-keys debug
        ;;                                   [:script-comments :script-trace]))
        lift-options (select-keys options lift-options)
        phases (or (seq phases)
                   (apply total-order-merge
                          (map :default-phases (concat groups targets))))]
    (doseq [group groups] (check-group-spec group))
    (logging/trace "Lift ready to start")
    (go-try ch
      (>! ch
          (let [session (session/create
                         {:executor (ssh/ssh-executor)
                          :plan-state (in-memory-plan-state initial-plan-state)
                          :user user/*admin-user*})
                nodes-set (all-group-nodes compute groups all-node-set)
                nodes-set (concat nodes-set targets)
                ;; TODO put nodes-set target maps into :extensions
                _ (when-not (or compute (seq nodes-set))
                    (throw (ex-info "No source of nodes"
                                    {:error :no-nodes-and-no-compute-service})))
                _ (logging/trace "Retrieved nodes")
                c (chan)
                _ (lift-op*
                   session
                   (concat
                    (when os-detect [:pallet/os])
                    [:settings])
                   nodes-set
                   {}
                   c)
                [settings-results e] (<! c)
                errs (errors settings-results)
                result {:results settings-results
                        :session session}]
            (cond
             e [result e]
             errs [result (ex-info "settings phase failed" {:errors errs})]
             :else (do
                     (lift-op* session phases nodes-set lift-options c)
                     (let [[lift-results e] (<! c)
                           errs (errors lift-results)
                           result (update-in result [:results]
                                             concat lift-results)]
                       [result (or e
                                   (if errs
                                     (ex-info "phase failed"
                                              {:errors errs})))]))))))))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified
phases.  The compute service may be supplied as an option, otherwise the
bound compute-service is used.  The configure phase is applied by default
unless other phases are specified.

node-set can be a group spec, a sequence of group specs, or a map
of group specs to nodes. Examples:

    [group-spec1 group-spec2 {group-spec #{node1 node2}}]
    group-spec
    {group-spec #{node1 node2}}

## Options:

`:compute`
: a compute service.

`:blobstore`
: a blobstore service.

`:phase`
: a phase keyword, phase function, or sequence of these.

`:user`
the admin-user on the nodes.

### Partitioning

`:partition-f`
: a function that takes a sequence of targets, and returns a sequence of
  sequences of targets.  Used to partition or filter the targets.  Defaults to
  any :partition metadata on the phase, or no partitioning otherwise.

## Post phase options

`:post-phase-f`
: specifies an optional function that is run after a phase is applied.  It is
  passed `targets`, `phase` and `results` arguments, and is called before any
  error checking is done.  The return value is ignored, so this is for side
  affect only.

`:post-phase-fsm`
: specifies an optional fsm returning function that is run after a phase is
  applied.  It is passed `targets`, `phase` and `results` arguments, and is
  called before any error checking is done.  The return value is ignored, so
  this is for side affect only.

### Asynchronous and Timeouts

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out.

### Algorithm options

`:phase-execution-f`
: specifies the function used to execute a phase on the targets.  Defaults
  to `pallet.core.primitives/build-and-execute-phase`.

`:execution-settings-f`
: specifies a function that will be called with a node argument, and which
  should return a map with `:user`, `:executor` and `:executor-status-fn` keys.

### OS detection

`:os-detect`
: controls detection of nodes' os (default true)."
  [node-set & {:keys [compute phase user all-node-set environment
                      async timeout-ms timeout-val
                      partition-f post-phase-f post-phase-fsm
                      phase-execution-f execution-settings-f
                      debug plan-state]
               :as options}]
  (logging/trace "Lift")
  ;; TODO (load-plugins)
  (let [ch (chan)]
    (lift* node-set ch options)
    (exec-operation
     ch
     (select-keys
      options [:async :operation :status-chan :close-status-chan?
               :timeout-ms :timeout-val]))))

(defn lift-nodes
  "Lift `targets`, a sequence of node-maps, using the specified `phases`.  This
provides a way of lifting phases, which doesn't tie you to working with all
nodes in a group.  Consider using this only if the functionality in `lift` is
insufficient.

`phases`
: a sequence of phase keywords (identifying phases) or plan functions, that
  should be applied to the target nodes.  Note that there are no default phases.

## Options:

`:user`
: the admin-user to use for operations on the target nodes.

`:environment`
: an environment map, to be merged into the environment.

`:plan-state`
: an state map, which can be used to passing settings across multiple lift-nodes
  invocations.

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [targets phases
   & {:keys [user environment plan-state async timeout-ms timeout-val]
      :or {environment {} plan-state {}}
      :as options}]
  (let [[phases phase-map] (process-phases phases)
        targets (groups-with-phases targets phase-map)
        environment (merge-environments
                     environment
                     (select-keys options environment-args))]
    (letfn [(lift-nodes* [operation]
              ;; (lift-partitions
              ;;  operation
              ;;  targets plan-state environment phases
              ;;  (dissoc options
              ;;          :environment :plan-state :async
              ;;          :timeout-val :timeout-ms))
              )]
      (exec-operation lift-nodes* options))))

(defn group-nodes
  "Return a sequence of node-maps for each node in the specified group-specs.

## Options:

`:async`
: a flag to control whether the function executes asynchronously.  When truthy,
  the function returns an Operation that can be deref'd like a future.  When not
  truthy, `:timeout-ms` may be used to specify a timeout.  Defaults to nil.

`:timeout-ms`
: an integral number of milliseconds to wait for completion before timeout.
  Only applies if `:async` is not truthy (the default).

`:timeout-val`
: a value to be returned should the operation time out."
  [compute groups & {:keys [async timeout-ms timeout-val] :as options}]
  (letfn [(group-nodes* [operation] (group-nodes operation compute groups))]
    ;; TODO
    ;; (exec-operation group-nodes* options)
    ))