(ns pallet.crate-install-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-actions]]
   [pallet.crate :refer [assoc-settings]]
   [pallet.crate-install :refer :all]))

(deftest install-test
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :packages
                            :packages []})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :package-source
                            :package-source {:name "my-source"}
                            :packages []
                            :package-options {}})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :rpm
                            :rpm {:remote-file "http://somewhere.com/"
                                  :name "xx"}})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :rpm-repo
                            :rpm {:remote-file "http://somewhere.com/"
                                  :name "xx"}
                            :packages []})
        (install :f nil)))
  (is (build-actions {}
        (assoc-settings :f {:install-strategy :deb
                            :debs {:remote-file "http://somewhere.com/"
                                   :name "xx"}
                            :package-source {:name "xx" :apt {:path "abc"}}
                            :packages []})
        (install :f nil))))
