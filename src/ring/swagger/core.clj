(ns ring.swagger.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [ring.util.response :refer :all]
            [ring.swagger.impl :refer :all]
            [schema.core :as s]
            [schema.macros :as sm]
            [plumbing.core :refer :all]
            [schema.utils :as su]
            [ring.swagger.schema :as schema]
            [ring.swagger.coerce :as coerce]
            [ring.swagger.common :refer :all]
            [ring.swagger.json-schema :as jsons]
            [ring.swagger.spec :as spec]
            [cheshire.generate :refer [add-encoder]])
  (:import [com.fasterxml.jackson.core JsonGenerator]))

;;
;; Models
;;

#_(s/defschema Route {:method   s/Keyword
                    :uri      [s/Any]
                    :metadata {s/Keyword s/Any}})

#_(s/defschema ResponseMessage {:code Long
                              (s/optional-key :message) String
                              (s/optional-key :responseModel) s/Any})

;;
;; JSON Encoding
;;

(add-encoder schema.utils.ValidationError
  (fn [x ^JsonGenerator jg]
    (.writeString jg
      (str (su/validation-error-explain x)))))

(defn date-time-encoder [x ^JsonGenerator jg]
  (.writeString jg (coerce/unparse-date-time x)))

(add-encoder java.util.Date date-time-encoder)
(add-encoder org.joda.time.DateTime date-time-encoder)

(add-encoder org.joda.time.LocalDate
  (fn [x ^JsonGenerator jg]
    (.writeString jg (coerce/unparse-date x))))

;;
;; Schema transformations
;;

#_(defn- plain-map?
  [x]
  (or
    (instance? clojure.lang.APersistentMap x)
    (instance? flatland.ordered.map.OrderedMap x)))

#_(defn- full-name [path] (->> path (map name) (map ->CamelCase) (apply str) symbol))
#_(defn- collect-schemas [keys schema]
  (cond
    (plain-map? schema)
    (if (and (seq (pop keys)) (s/schema-name schema))
      schema
      (with-meta
        (into (empty schema)
              (for [[k v] schema
                    :when (jsons/not-predicate? k)
                    :let [keys (conj keys (s/explicit-schema-key k))]]
                [k (collect-schemas keys v)]))
        {:name (full-name keys)}))

    (valid-container? schema)
    (contain schema (collect-schemas keys (first schema)))

    :else schema))

#_(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  [schema]
  (collect-schemas [(or (s/schema-name schema) (gensym "schema"))] schema))

#_(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:id (s/schema-name schema)
       :properties (jsons/properties schema)
       :required required})))

#_(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema (s/schema-name x)]
          (swap! schemas assoc schema (if (var? x) @x x)))
        x)
      x)
    @schemas))

#_(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt key (comp transform val)))
       (into {})))

#_(defn extract-models [details]
  (let [route-meta (->> details
                        :routes
                        (map :metadata))
        return-models (->> route-meta
                           (keep :return)
                           flatten)
        body-models (->> route-meta
                         (mapcat :parameters)
                         (filter (fn-> :type (= :body)))
                         (keep :model)
                         flatten)
        response-models (->> route-meta
                             (mapcat :responseMessages)
                             (keep :responseModel)
                             flatten)
        all-models (->> (concat body-models return-models response-models)
                        flatten
                        (map with-named-sub-schemas))]
    (into {} (map (juxt s/schema-name identity) all-models))))

;;
;; Route generation
;;

#_(defn path-params [s]
  (map (comp keyword second) (re-seq #":(.[^:|(/]*)[/]?" s)))

#_(defn string-path-parameters [uri]
  (let [params (path-params uri)]
    (if (seq params)
      {:type :path
       :model (zipmap params (repeat String))})))

#_(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

#_(defn generate-nick [{:keys [method uri]}]
  (-> (str (name method) " " uri)
      (str/replace #"/" " ")
      (str/replace #"-" "_")
      (str/replace #":" " by ")
      ->camelCase))

#_(def swagger-defaults      {:swaggerVersion "1.2" :apiVersion "0.0.1"})
#_(def resource-defaults     {:produces ["application/json"]
                            :consumes ["application/json"]})
#_(def api-declaration-keys  [:title :description :termsOfServiceUrl :contact :license :licenseUrl])

#_(defn join-paths
  "Join several paths together with \"/\". If path ends with a slash,
   another slash is not added."
  [& paths]
  (str/replace (str/replace (str/join "/" (remove nil? paths)) #"([/]+)" "/") #"/$" ""))

#_(defn context
  "Context of a request. Defaults to \"\", but has the
   servlet-context in the legacy app-server environments."
  [{:keys [servlet-context]}]
  (if servlet-context (.getContextPath servlet-context) ""))

#_(defn basepath
  "extract a base-path from ring request. Doesn't return default ports
   and reads the header \"x-forwarded-proto\" only if it's set to value
   \"https\". (e.g. your ring-app is behind a nginx reverse https-proxy).
   Adds possible servlet-context when running in legacy app-server."
  [{:keys [scheme server-name server-port headers] :as request}]
  (let [x-forwarded-proto (headers "x-forwarded-proto")
        context (context request)
        scheme (if (= x-forwarded-proto "https") "https" (name scheme))
        port (if (#{80 443} server-port) "" (str ":" server-port))]
    (str scheme "://" server-name port context)))

;;
;; Convert parameters
;;

#_(defmulti ^:private extract-parameter
  (fn [{:keys [type]}]
    type))

#_(defmethod extract-parameter :body [{:keys [model type]}]
  (if model
    (vector
      (jsons/->parameter {:paramType type
                          :name (some-> model schema/extract-schema-name str/lower-case)}
                         (jsons/->json model :top true)))))

#_(defmethod extract-parameter :default [{:keys [model type] :as it}]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key (eval k))]]
      (jsons/->parameter {:paramType type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (jsons/->json v)))))

#_(defn convert-parameters [parameters]
  (mapcat extract-parameter parameters))

#_(sm/defn ^:always-validate convert-response-messages [messages :- [ResponseMessage]]
  (for [{:keys [responseModel] :as message} messages]
    (if (and responseModel (schema/named-schema? responseModel))
      (update-in message [:responseModel] (fn [x] (:type (jsons/->json x :top true))))
      (dissoc message :responseModel))))

;;
;; Routing
;;

#_(defn api-listing [parameters swagger]
  (response
    (merge
      swagger-defaults
      (select-keys parameters [:apiVersion])
      {:info (select-keys parameters api-declaration-keys)
       :apis (for [[api details] swagger]
               {:path (str "/" (name api))
                :description (or (:description details) "")})})))

#_(defn api-declaration [parameters swagger api basepath]
  (if-let [details (and swagger (swagger api))]
    (response
      (merge
        swagger-defaults
        resource-defaults
        (select-keys parameters [:apiVersion :produces :consumes])
        {:basePath basepath
         :resourcePath "/"
         :models (transform-models (extract-models details))
         :apis (for [{:keys [method uri metadata] :as route} (:routes details)
                     :let [{:keys [return summary notes nickname parameters responseMessages]} metadata]]
                 {:path (swagger-path uri)
                  :operations [(merge
                                 (jsons/->json return :top true)
                                 {:method (-> method name .toUpperCase)
                                  :summary (or summary "")
                                  :notes (or notes "")
                                  :nickname (or nickname (generate-nick route))
                                  :responseMessages (convert-response-messages responseMessages)
                                  :parameters (convert-parameters parameters)})]})}))))




;;
;; 2.0
;;

(def Anything {s/Keyword s/Any})
(def Nothing {})

(s/defschema Operation (-> spec/Operation
                           (schema-dissoc :parameters)
                           (assoc :parameters {(s/optional-key :body) s/Any
                                               (s/optional-key :query) s/Any
                                               (s/optional-key :path) s/Any
                                               (s/optional-key :header) s/Any
                                               (s/optional-key :form) s/Any})
                           (assoc :method (s/enum :get :put :post :delete :options :head :patch))))

(s/defschema Swagger (-> spec/Swagger
                         (dissoc :paths :definitions)
                         (assoc :paths {s/Str [Operation]})))

;;
;; defaults
;;

; TODO: implement


;;
;; Schema transformations
;;

(defn- requires-definition? [schema]
  (not (contains? #{nil Nothing Anything}
                  (s/schema-name schema))))

(defn extract-models [swagger]
  (let [route-meta      (->> swagger
                             :paths
                             vals
                             flatten)
        body-models     (->> route-meta
                             (map (comp :body :parameters))
                             (filter requires-definition?))
        response-models (->> route-meta
                             (map :responses)
                             (mapcat vals)
                             (map :schema)
                             (filter requires-definition?))
        all-models      (concat body-models response-models)]
    (distinct all-models)))

(defn transform [schema]
  (let [required (required-keys schema)
        required (if-not (empty? required) required)]
    (remove-empty-keys
      {:properties (jsons/properties schema)
       :required required})))

(defn collect-models [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when (requires-definition? x)
          (swap! schemas assoc (s/schema-name x) (if (var? x) @x x)))
        x)
      x)
    @schemas))

(defn transform-models [schemas]
  (->> schemas
       (map collect-models)
       (apply merge)
       (map (juxt (comp keyword key) (comp transform val)))
       (into {})))

;;
;; Paths, parameters, responses
;;

;; TODO implement
;; https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md#parameterObject

(defmulti ^:private extract-parameter first)

;; TODO need to autogenerate names for schemas?
;; TODO specific format for :name ?
(defmethod extract-parameter :body [[type model]]
  (if-let [schema-name (s/schema-name model)]
    (vector {:in          type
             :name        (name schema-name)
             :description ""
             :required    true
             :schema      (str "#/definitions/" schema-name)})))

;; TODO ->json should return :keyword types, or validate for strings?
(defmethod extract-parameter :default [[type model]]
  (if model
    (for [[k v] (-> model value-of strict-schema)
          :when (s/specific-key? k)
          :let [rk (s/explicit-schema-key (eval k))]]
      (jsons/->parameter {:in type
                          :name (name rk)
                          :required (s/required-key? k)}
                         (jsons/->json v)))))

(defn convert-parameters [parameters]
  (into [] (mapcat extract-parameter parameters)))

;; TODO validate incoming as in old version,
;; handle headers etc.
(defn convert-response-messages [responses]
  (letfn [(response-schema [schema]
            (if-let [name (s/schema-name schema)]
              (str "#/definitions/" name)
              (transform schema)))]
    (zipmap (keys responses)
            (map (fn [r] (update-in r [:schema] response-schema))
                 (vals responses)))))

(defn transform-path-operations
  "Returns a map with methods as keys and the Operation
   maps with parameters and responses transformed to comply
   with Swagger JSON spec as values"
  [operations]
  (into {} (map (juxt :method #(-> %
                                   (dissoc :method)
                                   (update-in [:parameters] convert-parameters)
                                   (update-in [:responses]  convert-response-messages)))
                operations)))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn extract-paths-and-definitions [swagger]
  (let [paths       (->> swagger
                         :paths
                         keys
                         (map swagger-path))
        methods     (->> swagger
                         :paths
                         vals
                         (map transform-path-operations))
        definitions (->> swagger
                         extract-models
                         transform-models)]
    (vector (zipmap paths methods) definitions)))

(defn swagger-json [swagger]
  (let [[paths definitions] (extract-paths-and-definitions swagger)]
    (-> swagger
        (assoc :paths paths)
        (assoc :definitions definitions))))

;;
;; spike
;;

;; https://github.com/swagger-api/swagger-spec/blob/master/schemas/v2.0/schema.json
;; https://github.com/swagger-api/swagger-spec/blob/master/examples/v2.0/json/petstore.json
;; https://github.com/swagger-api/swagger-spec/blob/master/versions/2.0.md

(def swagger {:swagger 2.0
              :info {:version "version"
                     :title "title"
                     :description "description"
                     :termsOfService "jeah"
                     :contact {:name "name"
                               :url "url"
                               :email "email"}
                     :licence {:name "name"
                               :url "url"}
                     :x-kikka "jeah"}
              :basePath "/"
              :consumes ["application/json" "application/edn"]
              :produces ["application/json" "application/edn"]
              :paths {"/api/:id" [{:method :get
                                   :tags [:tag1 :tag2 :tag3]
                                   :summary "summary"
                                   :description "description"
                                   :externalDocs {:url "url"
                                                  :description "more info"}
                                   :operationId "operationId"
                                   :consumes ["application/xyz"]
                                   :produces ["application/xyz"]
                                   :parameters {:body Nothing
                                                :query (merge Anything {:x Long :y Long})
                                                :path {:id String}
                                                :header Anything
                                                :form Anything}
                                   :responses {200 {:description "ok"
                                                    :schema {:sum Long}}
                                               :default {:description "error"
                                                         :schema {:code Long}}}}]}})

;; more test data
(s/defschema LegOfPet {:length Long})

(s/defschema Pet {:id Long
                  :name String
                  :leg LegOfPet
                  (s/optional-key :weight) Double})
(s/defschema NotFound {:message s/Str})

;; TODO how to define descriptions for params
;; TODO :form or :formData here 
(def swagger-with-models {:swagger 2.0
              :info {:version "version"
                     :title "title"
                     :description "description"
                     :termsOfService "jeah"
                     :contact {:name "name"
                               :url "url"
                               :email "email"}
                     :licence {:name "name"
                               :url "url"}
                     :x-kikka "jeah"}
              :basePath "/"
              :consumes ["application/json" "application/edn"]
              :produces ["application/json" "application/edn"]
              :paths {"/api/:id" [{:method :get
                                   :tags [:tag1 :tag2 :tag3]
                                   :summary "summary"
                                   :description "description"
                                   :externalDocs {:url "url"
                                                  :description "more info"}
                                   :operationId "operationId"
                                   :consumes ["application/xyz"]
                                   :produces ["application/xyz"]
                                   :parameters {:body Nothing
                                                :query (merge Anything {:x Long :y Long})
                                                :path {:id String}
                                                :header Anything
                                                :form Anything}
                                   :responses {200 {:description "ok"
                                                    :schema {:sum Long}}
                                               400 {:description "not found"
                                                    :schema NotFound}
                                               :default {:description "error"
                                                         :schema {:code Long}}}}]
                      "/api/pets" [{:method :get
                                   :parameters {:body Pet
                                                :query (merge Anything {:x Long :y Long})
                                                :path {:id String}
                                                :header Anything
                                                :form Anything}
                                   :responses {200 {:description "ok"
                                                    :schema {:sum Long}}
                                               :default {:description "error"
                                                         :schema {:code Long}}}}
                                   {:method :post
                                    :parameters {:body Pet
                                                 :query (merge Anything {:x Long :y Long})
                                                 :path {:id String}
                                                 :header Anything
                                                 :form Anything}
                                    :responses {200 {:description "ok"
                                                     :schema {:sum Long}}
                                                :default {:description "error"
                                                          :schema {:code Long}}}}]}})

(s/validate Swagger swagger)

(s/validate spec/Swagger (swagger-json swagger))
