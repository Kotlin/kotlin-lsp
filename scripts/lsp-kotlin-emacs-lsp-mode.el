(defun kotlin-lsp-server-start-fun (port)
     (list "kotlin-lsp.sh" "--socket" (number-to-string port)))
(with-eval-after-load 'lsp-mode
  (add-to-list 'lsp-language-id-configuration
	       '(kotlin-mode . "kotlin"))

  (lsp-register-client
   (make-lsp-client
    :new-connection (lsp-tcp-connection 'kotlin-lsp-server-start-fun)
    :activation-fn (lsp-activate-on "kotlin")
    :major-modes '(kotlin-mode)
    :priority -1
    :server-id 'kotlin-jb-lsp
    )
   )
  )
