(ns pallet.actions.direct.file-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [file sed]]
   [pallet.actions.direct.file :refer [file* write-md5-for-file]]
   [pallet.build-actions :refer [build-actions build-script]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [script]]
   [pallet.test-utils
    :refer [with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]))

(use-fixtures
 :once
 with-ubuntu-script-template
 with-bash-script-language
 with-no-source-line-comments
 (logging-threshold-fixture))

(deftest file-test
  (is (script-no-comment=
       (stevedore/checked-script "file file1" ("touch" file1))
       (file* {} nil "file1" {})))

  (is (script-no-comment=
       (stevedore/checked-script "file file1" ("touch" file1))
       (first (build-actions [session {}]
                (file session "file1")))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (build-script [session {}]
         (file session "file1" {:owner "user1"}))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chown" user1 file1))
       (first (build-actions [session {}]
                (file session "file1" {:owner "user1" :action :create})))))
  (is (script-no-comment=
       (stevedore/checked-script
        "file file1"
        ("touch" file1)
        ("chgrp" group1 file1))
       (first (build-actions [session {}]
                (file session "file1" {:group "group1" :action :touch})))))
  (is (script-no-comment=
       (stevedore/checked-script
        "delete file file1"
        ("rm" "--force" file1))
       (first (build-actions [session {}]
                (file session "file1" {:action :delete :force true}))))))

(deftest sed-test
  (is (script-no-comment=
       (stevedore/checked-commands
        "sed file path"
        (script (~lib/sed-file "path" {"a" "b"} {:seperator "|"}))
        (write-md5-for-file "path" "path.md5"))
       (first
        (build-actions [session {}]
          (sed session "path" {"a" "b"} :seperator "|")))))
  (is
   (build-actions [session {}]
     (sed session "path" {"a" "b"} :seperator "|"))))
