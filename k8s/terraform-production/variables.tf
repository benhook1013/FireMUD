variable "kubeconfig" {
  description = "Path to kubeconfig for the target cluster"
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace"
  type        = string
  default     = "firemud"
}

variable "velero_provider" {
  description = "Velero object storage provider (e.g., aws, gcp)"
  type        = string
}

variable "velero_bucket" {
  description = "Velero backup bucket name"
  type        = string
}
