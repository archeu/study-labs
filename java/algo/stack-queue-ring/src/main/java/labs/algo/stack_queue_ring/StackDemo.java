package labs.algo.stack_queue_ring;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Stack;

/**
 * 栈：只在一头进出，后进先出。
 * 1. 风控规则表达式的括号检查：遇到左括号压栈，遇到右括号和栈顶配对。
 * 2. 坑：java.util.Stack 和 ArrayDeque 都能 push/pop，但遍历顺序相反。
 */
public class StackDemo {

    /** 返回 -1 表示括号全配上了；否则返回出错的位置。 */
    static int checkBrackets(String rule) {
        Deque<Integer> stack = new ArrayDeque<>();   // 存左括号的位置
        for (int i = 0; i < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '(' || c == '[') {
                stack.push(i);
            } else if (c == ')' || c == ']') {
                if (stack.isEmpty()) return i;                    // 右括号多了
                char open = rule.charAt(stack.pop());
                if ((c == ')') != (open == '(')) return i;        // 种类对不上
            }
        }
        return stack.isEmpty() ? -1 : stack.peek();                // 左括号多了：报最近没配上的那个
    }

    public static void main(String[] args) {
        String[] rules = {
            "price > 100 && (qty < 10 || (side == BUY && acct in [A, B]))",
            "price > 100 && (qty < 10 || side == BUY",
            "(qty < 10 ] || side == SELL)",
        };
        System.out.println("== 1 括号检查（栈）==");
        for (String r : rules) {
            int bad = checkBrackets(r);
            System.out.println("  " + r);
            System.out.println(bad < 0 ? "  → 配上了"
                    : "  " + spaces(bad) + "^ 下标 " + bad + " 出错");
        }

        System.out.println("== 2 同样 push 1 2 3，遍历出来的顺序 ==");
        Stack<Integer> old = new Stack<>();
        Deque<Integer> dq = new ArrayDeque<>();
        for (int i = 1; i <= 3; i++) { old.push(i); dq.push(i); }
        System.out.println("  Stack       for-each：" + old + "   peek()=" + old.peek());
        System.out.println("  ArrayDeque  for-each：" + dq + "   peek()=" + dq.peek());
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }
}
