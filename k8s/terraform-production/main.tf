terraform {
  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
  }
}

provider "kubernetes" {
  config_path = var.kubeconfig
}

provider "helm" {
  kubernetes {
    config_path = var.kubeconfig
  }
}

resource "helm_release" "postgresql" {
  name       = "firemud-postgresql"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "postgresql-ha"
  version    = var.postgres_chart_version
  namespace  = var.namespace
  values = [
    templatefile("${path.module}/postgres-values.yaml.tftpl", {
      postgres_superuser_password = var.postgres_superuser_password
      postgres_app_username       = var.postgres_app_username
      postgres_app_password       = var.postgres_app_password
      postgres_database           = var.postgres_database
    })
  ]
}

resource "helm_release" "redis_coord" {
  name       = "redis-coord"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "redis"
  version    = "28.3.1"
  namespace  = var.namespace
  values     = [file("${path.module}/redis-coord-values.yaml")]
}

resource "helm_release" "redis_cache" {
  name       = "redis-cache"
  repository = "https://charts.bitnami.com/bitnami"
  chart      = "redis"
  version    = "28.3.1"
  namespace  = var.namespace
  values     = [file("${path.module}/redis-cache-values.yaml")]
}

resource "helm_release" "velero" {
  name       = "velero"
  repository = "https://vmware-tanzu.github.io/helm-charts"
  chart      = "velero"
  version    = "12.2.1"
  namespace  = var.namespace
  set {
    name  = "image.tag"
    value = "v1.18.4"
  }
  set {
    name  = "image.digest"
    value = "sha256:c89fb5b6d1fd6afd368e0f483e6f5555fd62851a1eada8ac5c72482e674ca17b"
  }
  set {
    name  = "configuration.backupStorageLocation[0].name"
    value = "default"
  }
  set {
    name  = "configuration.backupStorageLocation[0].provider"
    value = var.velero_provider
  }
  set {
    name  = "configuration.backupStorageLocation[0].bucket"
    value = var.velero_bucket
  }
  set {
    name  = "configuration.backupStorageLocation[0].prefix"
    value = var.velero_bucket_prefix
  }
  set {
    name  = "credentials.existingSecret"
    value = var.velero_credentials_secret
  }
  set {
    name  = "configuration.defaultVolumesToFsBackup"
    value = "false"
  }
}

locals {
  velero_schedule_docs = split("\n---\n", file("${path.module}/../velero/schedule.yaml"))
}

resource "kubernetes_manifest" "velero_schedule" {
  for_each  = { for idx, doc in local.velero_schedule_docs : idx => yamldecode(doc) }
  manifest  = each.value
  depends_on = [helm_release.velero]
}

locals {
  velero_verify_docs = split("\n---\n", file("${path.module}/../velero/verify-backups-cronjob.yaml"))
}

resource "kubernetes_manifest" "velero_verify" {
  for_each  = { for idx, doc in local.velero_verify_docs : idx => yamldecode(doc) }
  manifest  = each.value
  depends_on = [helm_release.velero]
}

locals {
  pg_dump_docs = split("\n---\n", file("${path.module}/../postgres/pg-dump-cronjob.yaml"))
}

resource "kubernetes_manifest" "pg_dump" {
  for_each  = { for idx, doc in local.pg_dump_docs : idx => yamldecode(doc) }
  manifest  = each.value
  depends_on = [helm_release.postgresql]
}
