"""Read digest-pinned MinIO build image authorities from trusted source Dockerfiles."""

import re
from pathlib import Path


def trusted_base_images(workspace: Path) -> dict[str, str]:
    references = []
    for name in ("server", "client"):
        source = workspace / f"docker/minio/{name}.Dockerfile"
        stages = re.findall(r"(?mi)^FROM\s+([^\s]+)(?:\s+AS\s+build)?\s*$", source.read_text())
        if len(stages) != 2:
            raise ValueError(f"{source} must have exactly two explicit build/runtime image stages")
        for image, repository in zip(stages, ("golang", "alpine"), strict=True):
            if not re.fullmatch(rf"{repository}:[A-Za-z0-9._-]+@sha256:[0-9a-f]{{64}}", image):
                raise ValueError(f"{source} requires an exact digest-pinned {repository} image")
        references.append(dict(zip(("builder", "runtime"), stages, strict=True)))
    if references[0] != references[1]:
        raise ValueError("MinIO server and client build/runtime image authorities disagree")
    return references[0]
