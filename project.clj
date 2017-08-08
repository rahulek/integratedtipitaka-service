(defproject integratedtipitaka-service "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [compojure "1.5.1"]
                 [ring/ring-defaults "0.2.1"]]
  :plugins [[lein-ring "0.9.7"]]
  :ring {:handler integratedtipitaka-service.handler/app}
  :profiles
    {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                        [org.clojure/data.json "0.2.6"]
					    [clj-http "3.1.0"]
                        [clojurewerkz/neocons "3.0.0"]
				        [com.novemberain/pantomime "2.8.0"]
						[org.clojure/core.async "0.2.385"]
                        [ring/ring-mock "0.3.0"]]}}
)