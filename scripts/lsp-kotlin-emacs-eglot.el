(use-package eglot
  :hook ((kotlin-mode kotlin-ts-mode) . (lambda () (eglot-ensure)))
  :ensure nil ;; use built-in eglot Emacs 29. or later
  :custom
  (eglot-autoshutdown t)
  (eglot-extend-to-xref t)
  (eglot-sync-connect 1)
  (eglot-connect-timeout 60)
  (eglot-report-progress t)
  :config
  (add-to-list 'eglot-server-programs
               '((kotlin-ts-mode kotlin-mode) . ("bash" "PATH-TO-KOTLIN-LSP/kotlin-lsp.sh" "--stdio"))))
