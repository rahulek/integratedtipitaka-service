(ns integratedtipitaka-service.handler
   (:require  [compojure.core :refer :all]
            [compojure.route :as route]
			[compojure.coercions :as coercions]
			[clojure.data.json :as json]
            [pantomime.mime :as pm]
            [pantomime.extract :as pe]
            [clojurewerkz.neocons.rest :as nr]
            [clojurewerkz.neocons.rest.nodes :as nn]
            [clojurewerkz.neocons.rest.relationships :as nrl]
            [clojurewerkz.neocons.rest.cypher :as cy]
            [clojurewerkz.neocons.rest.transaction :as tx]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
		    [clojure.core.async :as async]
	)
)

;======================= Globals ======================================
(def neo4j-conn      (nr/connect "http://localhost:7474/db/data/"))
(def def-input-lang  "Pāli")
(def def-translation-lang "Marāṭhī")
;=======================================================================


(defn- get-books
       []
;	(println "get-books API executing....")
	(try
	  (let [cypher-buffer  (new StringBuffer)]
	    (doto cypher-buffer
		  (.append "MATCH (n :Anchor) RETURN n.book")
		)
		(json/write-str 
		   (flatten 
		     (map #(vals %) (cy/tquery neo4j-conn (.toString cypher-buffer)))
		   )
		)
	  )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
      )
	)
)

(defn- get-lines
       [conn ctx lang start-line-number end-line-number]
;	(println "get-lines API executing....")
	(try
	  (let [cypher-buffer (new StringBuffer)]
	    (.append cypher-buffer (format "UNWIND range(%d, %d) AS lineNumber " start-line-number end-line-number))
		(if (= lang def-input-lang)
	      (.append cypher-buffer (format "MATCH (a :Anchor {book:\"%s\"})-[r :CONTAINS]->(l :Line) WHERE lineNumber IN r.lineNumber RETURN a.book AS Book, lineNumber AS LineNumber, l.text AS Line;" ctx))
		  ;else
		  (.append cypher-buffer (format "MATCH (a :Anchor {book:\"%s\"})-[r :CONTAINS]-(l :Line)-[:TRANSLATION]->(m :MLine) WHERE lineNumber IN r.lineNumber RETURN a.book AS Book, lineNumber AS LineNumber, m.text AS Line;" ctx))
	    )
	    (cy/tquery conn (.toString cypher-buffer))
	  )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
	  )
	)
)

(defn- get-lines-handler
       [ctx lang start-line-number end-line-number]
	(try
	  (let [ lang-to-query    (if (not (nil? lang))
	                           (do
	                            (case (clojure.string/lower-case lang)
	                              "pali"     def-input-lang
								  "marathi"  def-translation-lang
								  def-input-lang)
								)
								;else
								def-input-lang)
	         result (get-lines  
	                   neo4j-conn 
					   ctx 
					   lang-to-query 
					   start-line-number
					   end-line-number)
		   ]
		(if (zero? (count result))
		  (route/not-found "Required Line(s) do not exist.")
	   	  (json/write-str result))
	  )
	 (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
	 )
	)
)

(defn- get-num-lines
       [ctx unique?]
	(try
	  (let [cypher-buffer  (new StringBuffer)]
	    (if (and (not (nil? unique?))
				     (= "true" unique?))
		    (.append cypher-buffer (format "MATCH (a :Anchor {book:\"%s\"})-[rs :CONTAINS]->(ls :Line) RETURN COUNT(ls)\n" ctx))
		  ; else
		    (.append cypher-buffer (format "MATCH (a :Anchor {book:\"%s\"})-[rs :CONTAINS]->(:Line) RETURN SUM(SIZE(rs.lineNumber))\n" ctx))
	    )
		(json/write-str
		 {:context ctx
		  :num-lines (first (vals (first (cy/tquery neo4j-conn (.toString cypher-buffer)))))
		 }
		)
      )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
      )
	)
)

(defn- get-total-lines
       []
	(try
	  (let [cypher-buffer  (new StringBuffer)]
        (doto cypher-buffer
	      (.append "MATCH (as :Anchor) WITH COLLECT(as.book) AS books\n")
		  (.append "UNWIND range(0, size(books)-1) AS idx\n")
		  (.append "MATCH (a :Anchor {book:books[idx]})-[rs :CONTAINS]->(:Line)\n")
		  (.append "RETURN SUM(rs.count);")
	    )
	  
	  	(json/write-str
		  {
		    :total-lines (first (vals (first (cy/tquery neo4j-conn (.toString cypher-buffer)))))
		  }
		)
      )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
      )
	)
)

(defn- get-repeated-lines
       [ctx]
	(try
	  (let [cypher-buffer  (new StringBuffer)]
	    (if (not (nil? ctx))
		   (do
		     (.append cypher-buffer (format "MATCH (as :Anchor {book:\"%s\"}) " ctx))
			 (.append cypher-buffer "-[rs :CONTAINS]->(ls :Line) WHERE ls.count > 1 ") 
			 (.append cypher-buffer " RETURN as.book AS Book, ls.text AS Line, rs.lineNumber AS LineNumber ORDER BY SIZE(rs.lineNumber) DESC")
		   )
		   ;else (whole tipitaka)
		   (do
		     (.append cypher-buffer "MATCH (l :Line)<-[r :CONTAINS]-(a :Anchor) WHERE l.count > 1 ")
	         (.append cypher-buffer "RETURN l.text AS Line, a.book AS Book, r.lineNumber AS Lines ORDER BY l.count DESC, l.text;")
		   )
        )
		(json/write-str
		  (cy/tquery neo4j-conn (.toString cypher-buffer))
		)
      )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
      )
	)
)

(defn- get-num-translated-lines
       [ctx]
	(try
	  (let [cypher-buffer  (new StringBuffer)]
        (doto cypher-buffer
	      (.append (format "WITH \"%s\" AS bk " ctx))
		  (.append "MATCH (a :Anchor {book:bk})-[:CONTAINS]-(:Line)-[:TRANSLATION]->(m :MLine) ")
		  (.append "RETURN a.book AS Book, count(m) AS NumTranslatedLines")
	    )
	  
	    (let [resp (cy/tquery neo4j-conn (.toString cypher-buffer))]
		  (if (not (empty? resp))
	  	    (json/write-str (first resp))
		    ;else
			(json/write-str {"Book" (format "%s" ctx) "NumTranslatedLines" 0})
		  )
		)
      )
	  (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
      )
	)
)

(defn- translated-line-exists?
       [conn ctx original-line-number]
  (try
    (let [cypher-buffer      (new StringBuffer)]
      (doto cypher-buffer
	    (.append (format "WITH %d as lineNum MATCH p = (a: Anchor {book:\"%s\"})-[r :CONTAINS]-(l :Line)-[:TRANSLATION]->(m :MLine) " original-line-number ctx))
		(.append "WHERE lineNum in r.lineNumber ")
		(.append "RETURN length(p)")
	  )
;	  (println "translated-line-exists Query->" (.toString cypher-buffer))
	  (not (empty? (cy/tquery conn (.toString cypher-buffer))))
    )
    (catch Exception e
	    (println (.getMessage e))
		false
	)
  )	
) 



(defn- insert-translated-line
    [conn ctx original-line-number translated-line]
  (try
    (let [insertion-tx       (tx/begin-tx conn)
          cypher-buffer      (new StringBuffer)
         ]
	  (if (translated-line-exists? conn ctx original-line-number)
	    (doto cypher-buffer
		  (.append (format "WITH %d AS lineNum, \"%s\" AS text " original-line-number translated-line))
		  (.append (format "MATCH (a :Anchor {book:\"%s\"})-[r :CONTAINS]-(l :Line)-[:TRANSLATION]->(m :MLine) " ctx))
		  (.append "WHERE lineNum in r.lineNumber ")
		  (.append "SET m.text = text, m.modifiedcnt = m.modifiedcnt + 1, m.timestamp = timestamp();")
		)
		;else
        (doto cypher-buffer
	      (.append (format "WITH %d as lineNum, \"%s\" AS text, \"%s\" AS bk " original-line-number translated-line ctx))
	      (.append "MATCH (a :Anchor {book:bk})-[r :CONTAINS]->(l :Line) WHERE lineNum in r.lineNumber WITH l, text, bk, lineNum ")
		  (.append "CREATE (mline :MLine) SET mline.text = text, mline.modifiedcnt=0, mline.timestamp = timestamp() ")
		  (.append "CREATE (l)-[:TRANSLATION]->(mline)")
	    )
	  )
;	  (println "Insert Query->" (.toString cypher-buffer))
	  (tx/execute conn insertion-tx)  ;tx keep alive
	  (cy/tquery conn (.toString cypher-buffer))
	  (tx/commit conn insertion-tx)
    )
    (catch Exception e
	    (println (.getMessage e))
		{:status 500 :body "Internal Server Error"}
	)
  )
)

(defn- insert-handler 
      [request]
	  (try
	    (let [parsed (json/read-str (slurp (:body request)) 
	                                :key-fn keyword 
								    :value-fn (fn [k v]
									           (format "%s" v))
				      )
			   context ((:query-params request) "ctx")
			  ]
;	      (println "Request Ctx->" context)
;	      (println (format "Got POST with the request body as below.\n%s" parsed))
		  (json/write-str
		      (map (fn [e]
;		             (println "Processing-> Line:" (Integer/parseInt (:LineNumber e)) ",Text:" (:TranslatedText e))
				 
					 (insert-translated-line 
					     neo4j-conn
						 context
						 (Integer/parseInt (:LineNumber e))
						 (:TranslatedText e))
						 
					 {:Context context :LineNumber (:LineNumber e) :Message "Inserted successfully."}
				  )
			      parsed
		      )
		  )
	    )
	    (catch Exception e
	      (println (.getMessage e))
		  {:status 500 :body "Internal Server Error"}
	    )
	  )
)

(defroutes app-routes
  (GET "/" request (str request))
  (GET "/ping" request {:status 200 :body "Tipitaka Translation DB Server Alive and Kicking"})
  (GET "/books" request (get-books))
  (GET "/line" [ctx proclang number :<< coercions/as-int :as r] (get-lines-handler ctx proclang number number))
  (GET "/lines" [ctx
                 proclang
                 start :<< coercions/as-int 
                 end :<< coercions/as-int :as r]       (get-lines-handler ctx proclang start end))
   (GET "/numlines" [ctx unique] (get-num-lines ctx unique))
   (GET "/repeatedlines" [ctx] (get-repeated-lines ctx))
   (GET "/totallines"  request (get-total-lines))
   (GET "/numtranslatedlines" [ctx] (get-num-translated-lines ctx))
   (POST "/insert" request (insert-handler request))
   (route/not-found "Not Found")
)

(def app
  (wrap-defaults app-routes (assoc-in site-defaults [:security :anti-forgery] false))
)
