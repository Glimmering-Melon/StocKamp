"""
Neo4j Client - Singleton pattern for managing Neo4j connections.
Reads credentials from environment variables via python-dotenv.
"""

import os
from typing import Optional

from dotenv import load_dotenv

load_dotenv()


class Neo4jClient:
    """Singleton Neo4j client for all Knowledge Graph operations."""

    _instance: Optional["Neo4jClient"] = None

    def __init__(self) -> None:
        uri = os.getenv("NEO4J_URI", "bolt://localhost:7687")
        user = os.getenv("NEO4J_USER", "neo4j")
        password = os.getenv("NEO4J_PASSWORD", "")
        try:
            from neo4j import GraphDatabase
            self._driver = GraphDatabase.driver(uri, auth=(user, password))
            # Verify connectivity immediately
            self._driver.verify_connectivity()
        except Exception as e:
            raise ConnectionError(f"[connect] Failed to connect to Neo4j: {e}") from e

    @classmethod
    def get_instance(cls) -> "Neo4jClient":
        """Return the singleton instance, creating it if necessary."""
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def create_node(self, label: str, properties: dict) -> str:
        """
        MERGE a node with the given label and properties (upsert).
        Returns the node's element ID.
        """
        cypher = (
            f"MERGE (n:{label} {{"
            + ", ".join(f"{k}: ${k}" for k in properties)
            + "}) RETURN elementId(n) AS node_id"
        )
        try:
            with self._driver.session() as session:
                result = session.run(cypher, parameters=properties)
                record = result.single()
                return record["node_id"]
        except Exception as e:
            raise ConnectionError(f"[create_node] Operation failed: {e}") from e

    def create_edge(
        self,
        from_id: str,
        to_id: str,
        rel_type: str,
        properties: dict = {},
    ) -> None:
        """Create a directed edge between two nodes identified by their element IDs."""
        props_clause = ""
        if properties:
            props_clause = " {" + ", ".join(f"{k}: ${k}" for k in properties) + "}"

        cypher = (
            f"MATCH (a) WHERE elementId(a) = $from_id "
            f"MATCH (b) WHERE elementId(b) = $to_id "
            f"MERGE (a)-[r:{rel_type}{props_clause}]->(b)"
        )
        params = {"from_id": from_id, "to_id": to_id, **properties}
        try:
            with self._driver.session() as session:
                session.run(cypher, parameters=params)
        except Exception as e:
            raise ConnectionError(f"[create_edge] Operation failed: {e}") from e

    def run_query(self, cypher: str, params: dict = {}) -> list[dict]:
        """Execute an arbitrary Cypher query and return results as a list of dicts."""
        try:
            with self._driver.session() as session:
                result = session.run(cypher, parameters=params)
                return [record.data() for record in result]
        except Exception as e:
            raise ConnectionError(f"[run_query] Operation failed: {e}") from e

    def close_connection(self) -> None:
        """Close the Neo4j driver and release all resources."""
        try:
            self._driver.close()
        except Exception as e:
            raise ConnectionError(f"[close_connection] Failed to close driver: {e}") from e
        finally:
            Neo4jClient._instance = None
