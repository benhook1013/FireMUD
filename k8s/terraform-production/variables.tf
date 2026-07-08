variable "kubeconfig" {
  description = "Path to kubeconfig for the target cluster"
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace"
  type        = string
  default     = "firemud"
}

variable "postgres_superuser_password" {
  description = "PostgreSQL superuser password for the production Helm release"
  type        = string
  sensitive   = true
}

variable "postgres_app_username" {
  description = "Application PostgreSQL username for the production Helm release"
  type        = string
  default     = "firemud"
}

variable "postgres_app_password" {
  description = "Application PostgreSQL password for the production Helm release"
  type        = string
  sensitive   = true
}

variable "postgres_database" {
  description = "Application PostgreSQL database name for the production Helm release"
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

variable "velero_bucket_prefix" {
  description = "Prefix inside the backup bucket"
  type        = string
  default     = "postgres"
}

variable "velero_credentials_secret" {
  description = "Existing Kubernetes secret containing Velero credentials"
  type        = string
  default     = "velero-creds"
}
