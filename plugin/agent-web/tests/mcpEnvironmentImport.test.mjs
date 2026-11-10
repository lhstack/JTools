import test from "node:test";
import assert from "node:assert/strict";
import { parseEnvironmentImport } from "../src/mcpEnvironmentImport.js";
test("imports mixed separators, trims keys and values, retains separators inside values", () => {
  assert.deepEqual(
    parseEnvironmentImport(
      " A = value \r\n B: https://host:8443?a=b \n TOKEN=abc== \n EMPTY:  \n",
    ),
    { A: "value", B: "https://host:8443?a=b", TOKEN: "abc==", EMPTY: "" },
  );
});
test("reports invalid lines before importing and uses last duplicate", () => {
  assert.throws(() => parseEnvironmentImport("A=1\ninvalid"), /第 2 行/);
  assert.throws(() => parseEnvironmentImport(" :value"), /名称不能为空/);
  assert.throws(() => parseEnvironmentImport(" \n"), /请输入/);
  assert.deepEqual(parseEnvironmentImport("A=1\n A : 2 "), { A: "2" });
});
test("prototype-named keys remain ordinary own properties", () => {
  const result = parseEnvironmentImport("__proto__=literal\nconstructor:value");
  assert.equal(
    Object.getOwnPropertyDescriptor(result, "__proto__").value,
    "literal",
  );
  assert.equal(result.constructor, "value");
});
