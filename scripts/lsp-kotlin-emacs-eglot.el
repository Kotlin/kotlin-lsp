(use-package eglot
  :hook ((kotlin-mode kotlin-ts-mode) . (lambda () (eglot-ensure)))
  :ensure nil ;; use built-in eglot
  :custom
  (eglot-autoshutdown t)
  (eglot-extend-to-xref t)
  (eglot-sync-connect 1)
  (eglot-connect-timeout 60)
  (eglot-report-progress nil)
  (eglot-events-buffer-size 0)
  (eglot-events-buffer-config '(size: 0 :format full))
  :config
  (add-to-list 'eglot-server-programs
               '((kotlin-ts-mode kotlin-mode) . ("bash" "PATH-TO-KOTLIN-LSP/kotlin-lsp.sh" "--stdio"))))
