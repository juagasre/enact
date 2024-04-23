// eslint-disable-next-line @typescript-eslint/no-explicit-any
function deserializer(key: string, value: any): any {
  if (Array.isArray(value) && value.length === 3 && value[0] === "<<SET" && value[2] === "SET>>") {
    return new Set(parse(value[1]));
  }
  if (Array.isArray(value) && value.length === 3 && value[0] === "<<Function" && value[2] === "Function>>") {
    // eslint-disable-next-line no-new-func
    return new Function(`return ${value[1]}`)();
  }
  return value;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function serializer(key: string, value: unknown): any {
  if (value instanceof Set) {
    return ["<<SET", stringify(Array.from(value)), "SET>>"];
  }
  if (value instanceof Function) {
    return ["<<Function", value.toString(), "Function>>"];
  }
  return value;
}

/**
 * Use the following functions to serialize/deserialize data structures that may
 * include Sets:
 */
export function stringify(value: unknown): string {
  return JSON.stringify(value, serializer);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function parse<T = any>(value: string): T {
  return JSON.parse(value, deserializer) as T;
}
