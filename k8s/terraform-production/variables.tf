variable "kubeconfig" {
  description = "Path to kubeconfig for the target cluster"
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace"
  type        = string
  default     = "firemud"
}
