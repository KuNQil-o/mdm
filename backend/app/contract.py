"""Public, integration-oriented OpenAPI contract (the dispatcher stays internal)."""

from .service import RESOURCES


def document():
    obj = {
        "type": "object",
        "additionalProperties": False,
        "required": ["callerSystemCode", "categoryCode", "attributes"],
        "properties": {
            "callerSystemCode": {"type": "string", "maxLength": 80},
            "categoryCode": {"type": "string", "maxLength": 80},
            "sourceRecordKey": {"type": "string", "minLength": 1, "maxLength": 1000},
            "attributes": {"type": "object", "additionalProperties": True},
        },
    }
    issued = {**obj, "required": obj["required"] + ["sourceRecordKey"]}
    errors = {
        str(status): {
            "description": description,
            "content": {
                "application/json": {"schema": {"$ref": "#/components/schemas/Error"}}
            },
        }
        for status, description in [
            (400, "Malformed request"),
            (401, "Authentication required"),
            (403, "Authorization denied"),
            (409, "Conflict"),
            (422, "Validation or configuration error"),
            (429, "Rate limited"),
            (503, "Retry with original Idempotency-Key"),
        ]
    }
    header = {
        "name": "Idempotency-Key",
        "in": "header",
        "required": True,
        "schema": {"type": "string", "maxLength": 200},
    }
    success = {
        "200": {
            "description": "Number issued or reused",
            "content": {
                "application/json": {
                    "schema": {"$ref": "#/components/schemas/AssignmentResult"}
                }
            },
        }
    }
    post = lambda schema: {
        "requestBody": {
            "required": True,
            "content": {"application/json": {"schema": schema}},
        },
        "responses": {**success, **errors},
    }
    paths = {
        "/material-number-assignments": {
            "post": {
                **post({"$ref": "#/components/schemas/AssignmentRequest"}),
                "summary": "Issue once or reuse an existing assignment",
                "security": [{"CallerKey": []}],
                "parameters": [header],
            },
            "get": {
                "summary": "Search assignments (tenant and category scoped)",
                "security": [{"Session": []}, {"CallerKey": []}],
                "parameters": [
                    {"name": x, "in": "query", "schema": {"type": "string"}}
                    for x in [
                        "categoryCode",
                        "materialNo",
                        "callerSystemCode",
                        "sourceRecordKey",
                        "identityHash",
                        "identityCanonical",
                        "from",
                        "to",
                        "limit",
                        "offset",
                    ]
                ],
                "responses": {
                    "200": {"description": "items / total / limit / offset"},
                    **errors,
                },
            },
        },
        "/material-number-previews": {
            "post": {
                **post({"$ref": "#/components/schemas/PreviewRequest"}),
                "summary": "Normalize, validate and explain without consuming sequence",
                "security": [{"Session": []}, {"CallerKey": []}],
            }
        },
    }
    for route, parameters in [
        ("material-number-assignments/{assignmentId}", ["assignmentId"]),
        ("material-number-assignments/by-no/{materialNo}", ["materialNo"]),
        (
            "material-number-assignments/by-source/{callerSystemCode}/{sourceRecordKey}",
            ["callerSystemCode", "sourceRecordKey"],
        ),
    ]:
        paths["/" + route] = {
            "get": {
                "summary": "Read immutable ledger and full explanation",
                "parameters": [
                    {
                        "name": x,
                        "in": "path",
                        "required": True,
                        "schema": {"type": "string"},
                    }
                    for x in parameters
                ],
                "responses": {
                    "200": {
                        "description": "Assignment snapshots, configurationSnapshot, sourceBindings"
                    },
                    **errors,
                },
            }
        }
    for resource in list(RESOURCES) + ["caller-systems", "release-packages"]:
        paths["/" + resource] = {
            "get": {
                "summary": "List " + resource,
                "responses": {
                    "200": {"description": "Versioned resource list"},
                    **errors,
                },
            },
            "post": {**post({"type": "object"}), "summary": "Create " + resource},
        }
        paths["/" + resource + "/{id}"] = {
            "parameters": [
                {
                    "name": "id",
                    "in": "path",
                    "required": True,
                    "schema": {"type": "string", "format": "uuid"},
                }
            ],
            "get": {
                "responses": {
                    "200": {"description": "Resource with ETag / rowVersion"},
                    **errors,
                }
            },
            "patch": {
                **post({"type": "object"}),
                "parameters": [
                    {
                        "name": "If-Match",
                        "in": "header",
                        "required": True,
                        "schema": {"type": "string"},
                    }
                ],
            },
        }
    for action in ["test", "submit", "publish", "reject", "retire"]:
        paths["/release-packages/{id}/" + action] = {
            "post": {
                "summary": "Release " + action,
                "parameters": [
                    {
                        "name": "id",
                        "in": "path",
                        "required": True,
                        "schema": {"type": "string", "format": "uuid"},
                    },
                    {
                        "name": "If-Match",
                        "in": "header",
                        "required": True,
                        "schema": {"type": "string"},
                    },
                ],
                "responses": {
                    "200": {
                        "description": "Release with regression, diff and approval evidence"
                    },
                    **errors,
                },
            }
        }
    preview = {
        **obj,
        "properties": {
            **obj["properties"],
            "releasePackageVersionId": {
                "type": "string",
                "format": "uuid",
                "description": "Draft/review preview for designers only",
            },
        },
    }
    return {
        "openapi": "3.0.3",
        "info": {"title": "Material Identity & Number Governance", "version": "4.0.0"},
        "servers": [{"url": "/api/v1"}],
        "security": [{"Session": []}],
        "paths": paths,
        "components": {
            "securitySchemes": {
                "CallerKey": {"type": "apiKey", "in": "header", "name": "X-Caller-Key"},
                "Session": {"type": "apiKey", "in": "cookie", "name": "mdm_session"},
            },
            "schemas": {
                "AssignmentRequest": issued,
                "PreviewRequest": preview,
                "AssignmentResult": {
                    "type": "object",
                    "required": ["assignmentId", "materialNo", "status", "reused"],
                    "properties": {
                        "assignmentId": {"type": "string", "format": "uuid"},
                        "requestId": {"type": "string"},
                        "traceId": {"type": "string", "minLength": 32, "maxLength": 32},
                        "materialNo": {"type": "string", "maxLength": 128},
                        "status": {"type": "string", "enum": ["ISSUED", "REUSED"]},
                        "reused": {"type": "boolean"},
                        "categoryCode": {"type": "string"},
                        "releaseVersion": {"type": "string"},
                        "releasePackageVersionId": {"type": "string", "format": "uuid"},
                        "issuedAt": {"type": "string", "format": "date-time"},
                    },
                },
                "Error": {
                    "type": "object",
                    "required": ["requestId", "code", "message", "details"],
                    "properties": {
                        "requestId": {"type": "string"},
                        "traceId": {"type": "string", "minLength": 32, "maxLength": 32},
                        "code": {"type": "string"},
                        "message": {"type": "string"},
                        "details": {"type": "array", "items": {"type": "object"}},
                    },
                },
            },
        },
    }
