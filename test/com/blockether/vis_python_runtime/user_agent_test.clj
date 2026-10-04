(ns com.blockether.vis-python-runtime.user-agent-test
  "What urllib sends as `User-Agent` from a sandbox session. `vis_runtime.install`
   names the runtime instead of the stock `Python-urllib/3.x`, which the Cloudflare
   browser integrity check refuses with 403 (error 1010). No request here leaves
   the process: a handler that runs before urllib's own HTTP handler records the
   header and answers."
  (:require [clojure.test :refer [is testing use-fixtures]]
            [com.blockether.vis-python-runtime.harness :as harness :refer [block]]))

(use-fixtures :each
              (fn [run]
                (try (run) (finally (harness/close-sessions!)))))

(def ^:private recorder
  "A block that defines `sent(headers)`: the User-Agent that urllib puts on one
   request, recorded before a socket opens."
  (str "import io, urllib.request, urllib.response\n"
       "class Seen(urllib.request.BaseHandler):\n" "    handler_order = 100\n"
       "    def http_open(self, req):\n" "        self.agent = req.get_header('User-agent')\n"
       "        answer = urllib.response.addinfourl(io.BytesIO(b''), {}, req.full_url, 200)\n"
       "        answer.msg = 'OK'\n"
       "        return answer\n" "def sent(headers=None):\n"
       "    seen = Seen()\n"
       "    request = urllib.request.Request('http://127.0.0.1/', headers=headers or {})\n"
       "    urllib.request.build_opener(seen).open(request)\n" "    return seen.agent\n"))

(harness/defbuilt-test
  default-user-agent-test
  (let [session
        (harness/block-session)

        agent
        (str "vis-python/" (harness/ev session "VIS_PYTHON_RUNTIME_VERSION"))]

    (is (nil? (:error (block session recorder))))
    (testing "a request without its own header names the runtime"
      (is (= agent (harness/ev session "sent()"))))
    (testing "a request that sets User-Agent keeps it"
      (is (= "mine/1" (harness/ev session "sent({'User-Agent': 'mine/1'})"))))
    (testing "every opener that urllib builds names the runtime"
      (is (= agent
             (harness/ev session "dict(urllib.request.build_opener().addheaders)['User-agent']"))))
    (testing "a second session installs without a second wrapper"
      (harness/block-session)
      (is (= agent (harness/ev session "sent()")))
      (is (harness/truthy
            session
            "not hasattr(urllib.request.OpenerDirector.__init__.__wrapped__, '__wrapped__')")))))
