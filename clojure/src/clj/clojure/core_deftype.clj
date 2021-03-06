;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(in-ns 'clojure.core)

;;;;;;;;;;;;;;;;;;;;;;;;;;;; definterface ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn namespace-munge
  "Clojureの名前空間の名前をJavaパッケージ名として許される形に変換します。"
  {:added "1.2"}
  [ns]
  (.replace (str ns) \- \_))

;for now, built on gen-interface
(defmacro definterface
  "引数に与えられた名前とメソッドシグネチャからJavaのインタフェースを生成します。
  メソッドの戻り値の型と引数の型を型ヒントで指定することができます。
  型の指定がない場合はObjectになります。

  (definterface MyInterface
    (^int method1 [x])
    (^Bar method2 [^Baz b ^Quux q]))"
  {:added "1.2"} ;; Present since 1.2, but made public in 1.5.
  [name & sigs]
  (let [tag (fn [x] (or (:tag (meta x)) Object))
        psig (fn [[name [& args]]]
               (vector name (vec (map tag args)) (tag name) (map meta args)))
        cname (with-meta (symbol (str (namespace-munge *ns*) "." name)) (meta name))]
    `(let [] 
       (gen-interface :name ~cname :methods ~(vec (map psig sigs)))
       (import ~cname))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;; reify/deftype ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-opts [s]
  (loop [opts {} [k v & rs :as s] s]
    (if (keyword? k)
      (recur (assoc opts k v) rs)
      [opts s])))

(defn- parse-impls [specs]
  (loop [ret {} s specs]
    (if (seq s)
      (recur (assoc ret (first s) (take-while seq? (next s)))
             (drop-while seq? (next s)))
      ret)))

(defn- parse-opts+specs [opts+specs]
  (let [[opts specs] (parse-opts opts+specs)
        impls (parse-impls specs)
        interfaces (-> (map #(if (var? (resolve %)) 
                               (:on (deref (resolve %)))
                               %)
                            (keys impls))
                       set
                       (disj 'Object 'java.lang.Object)
                       vec)
        methods (map (fn [[name params & body]]
                       (cons name (maybe-destructured params body)))
                     (apply concat (vals impls)))]
    (when-let [bad-opts (seq (remove #{:no-print} (keys opts)))]
      (throw (IllegalArgumentException. (apply print-str "Unsupported option(s) -" bad-opts))))
    [interfaces methods opts]))

(defmacro reify 
  "reifyは次のような構成のマクロです：

 (reify options* specs*)
  
  現在、提供されているオプションはありません。

  各specは、プロトコル名もしくはインタフェース名と、それに続く0個以上の
  メソッド定義からなります：

  protocol-or-interface-or-Object
  (methodName [args+] body)*

  プロトコルやインタフェースがもつすべてのメソッドに対して、実装を与える
  ようにするべきです。Objectで定義されているメソッドをオーバーライドする
  こともできます。第1引数として、ターゲットオブジェクト(Javaでいう'this')を
  受ける引数がなければならないことに注意して下さい。したがって、インタフェースに
  対するメソッドは、インタフェースの宣言にあるよりも1つ多く引数をとることに
  なります。メソッドの先頭へ戻るrecurの呼び出しにはターゲットオブジェクトを
  渡すべきではないことにも注意して下さい。ターゲットオブジェクトは自動的に
  受け渡され、他のオブジェクトと置き換えることはできません。

  戻り値の型および引数の型は、それぞれメソッド名および引数名へ型ヒントを
  付加することにより指定できます。型ヒントをすべて付加しないままにした場合、
  プロトコルまたはインタフェースから、名前と引数の個数がマッチするメソッドが
  自動的に選ばれます。通常はこのようにするのがよいでしょう。型ヒントを
  付加した場合、推論はまったくされないため、戻り値の型とすべての引数の型を
  型ヒントで正しく指定しなければなりません(Objectの場合は省略可能)。
  プロトコルやインタフェースでメソッドがオーバーロードされている場合、
  オーバーロードされたメソッドを別のオーバーロードされたメソッドの定義中で
  呼び出してはいけません。インタフェースが、引数の個数の同じオーバーロード
  されたメソッドを複数もつ場合、戻り値の型と引数の型を型ヒントで指定し、
  メソッドを特定しなければなりません。型ヒントを省略すると、Objectとして
  解釈されます。

  reifyのメソッド内はレキシカルクロージャであり、外側のローカルスコープを
  参照することができます：
  
  (str (let [f \"foo\"] 
       (reify Object 
         (toString [this] f))))
  == \"foo\"

  (seq (let [f \"foo\"] 
       (reify clojure.lang.Seqable 
         (seq [this] (seq f)))))
  == (\\f \\o \\o))
  
  reifyは常にclojure.lang.IObjを実装したオブジェクトを生成します。reifyの
  フォームにメタデータが付加されている場合は、生成されるオブジェクトにその
  メタデータが付加されます。
  
  (meta ^{:k :v} (reify Object (toString [this] \"foo\")))
  == {:k :v}"
  {:added "1.2"} 
  [& opts+specs]
  (let [[interfaces methods] (parse-opts+specs opts+specs)]
    (with-meta `(reify* ~interfaces ~@methods) (meta &form))))

(defn hash-combine [x y] 
  (clojure.lang.Util/hashCombine x (clojure.lang.Util/hash y)))

(defn munge [s]
  ((if (symbol? s) symbol str) (clojure.lang.Compiler/munge (str s))))

(defn- imap-cons
  [^IPersistentMap this o]
  (cond
   (instance? java.util.Map$Entry o)
     (let [^java.util.Map$Entry pair o]
       (.assoc this (.getKey pair) (.getValue pair)))
   (instance? clojure.lang.IPersistentVector o)
     (let [^clojure.lang.IPersistentVector vec o]
       (.assoc this (.nth vec 0) (.nth vec 1)))
   :else (loop [this this
                o o]
      (if (seq o)
        (let [^java.util.Map$Entry pair (first o)]
          (recur (.assoc this (.getKey pair) (.getValue pair)) (rest o)))
        this))))

(defn- emit-defrecord 
  "Do not use this directly - use defrecord"
  {:added "1.2"}
  [tagname name fields interfaces methods]
  (let [classname (with-meta (symbol (str (namespace-munge *ns*) "." name)) (meta name))
        interfaces (vec interfaces)
        interface-set (set (map resolve interfaces))
        methodname-set (set (map first methods))
        hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))
        base-fields fields
        fields (conj fields '__meta '__extmap)
        type-hash (hash classname)]
    (when (some #{:volatile-mutable :unsynchronized-mutable} (mapcat (comp keys meta) hinted-fields))
      (throw (IllegalArgumentException. ":volatile-mutable or :unsynchronized-mutable not supported for record fields")))
    (let [gs (gensym)]
    (letfn 
     [(irecord [[i m]]
        [(conj i 'clojure.lang.IRecord)
         m])
      (eqhash [[i m]] 
        [(conj i 'clojure.lang.IHashEq)
         (conj m
               `(hasheq [this#] (bit-xor ~type-hash (clojure.lang.APersistentMap/mapHasheq this#)))
               `(hashCode [this#] (clojure.lang.APersistentMap/mapHash this#))
               `(equals [this# ~gs] (clojure.lang.APersistentMap/mapEquals this# ~gs)))])
      (iobj [[i m]] 
            [(conj i 'clojure.lang.IObj)
             (conj m `(meta [this#] ~'__meta)
                   `(withMeta [this# ~gs] (new ~tagname ~@(replace {'__meta gs} fields))))])
      (ilookup [[i m]] 
         [(conj i 'clojure.lang.ILookup 'clojure.lang.IKeywordLookup)
          (conj m `(valAt [this# k#] (.valAt this# k# nil))
                `(valAt [this# k# else#] 
                   (case k# ~@(mapcat (fn [fld] [(keyword fld) fld]) 
                                       base-fields)
                         (get ~'__extmap k# else#)))
                `(getLookupThunk [this# k#]
                   (let [~'gclass (class this#)]              
                     (case k#
                           ~@(let [hinted-target (with-meta 'gtarget {:tag tagname})] 
                               (mapcat 
                                (fn [fld]
                                  [(keyword fld)
                                   `(reify clojure.lang.ILookupThunk
                                           (get [~'thunk ~'gtarget]
                                                (if (identical? (class ~'gtarget) ~'gclass)
                                                  (. ~hinted-target ~(symbol (str "-" fld)))
                                                  ~'thunk)))])
                                base-fields))
                           nil))))])
      (imap [[i m]] 
            [(conj i 'clojure.lang.IPersistentMap)
             (conj m 
                   `(count [this#] (+ ~(count base-fields) (count ~'__extmap)))
                   `(empty [this#] (throw (UnsupportedOperationException. (str "Can't create empty: " ~(str classname)))))
                   `(cons [this# e#] ((var imap-cons) this# e#))
                   `(equiv [this# ~gs] 
                        (boolean 
                         (or (identical? this# ~gs)
                             (when (identical? (class this#) (class ~gs))
                               (let [~gs ~(with-meta gs {:tag tagname})]
                                 (and  ~@(map (fn [fld] `(= ~fld (. ~gs ~(symbol (str "-" fld))))) base-fields)
                                       (= ~'__extmap (. ~gs ~'__extmap))))))))
                   `(containsKey [this# k#] (not (identical? this# (.valAt this# k# this#))))
                   `(entryAt [this# k#] (let [v# (.valAt this# k# this#)]
                                            (when-not (identical? this# v#)
                                              (clojure.lang.MapEntry. k# v#))))
                   `(seq [this#] (seq (concat [~@(map #(list `new `clojure.lang.MapEntry (keyword %) %) base-fields)] 
                                              ~'__extmap)))
                   `(iterator [this#] (clojure.lang.SeqIterator. (.seq this#)))
                   `(assoc [this# k# ~gs]
                     (condp identical? k#
                       ~@(mapcat (fn [fld]
                                   [(keyword fld) (list* `new tagname (replace {fld gs} fields))])
                                 base-fields)
                       (new ~tagname ~@(remove #{'__extmap} fields) (assoc ~'__extmap k# ~gs))))
                   `(without [this# k#] (if (contains? #{~@(map keyword base-fields)} k#)
                                            (dissoc (with-meta (into {} this#) ~'__meta) k#)
                                            (new ~tagname ~@(remove #{'__extmap} fields) 
                                                 (not-empty (dissoc ~'__extmap k#))))))])
      (ijavamap [[i m]]
                [(conj i 'java.util.Map 'java.io.Serializable)
                 (conj m
                       `(size [this#] (.count this#))
                       `(isEmpty [this#] (= 0 (.count this#)))
                       `(containsValue [this# v#] (boolean (some #{v#} (vals this#))))
                       `(get [this# k#] (.valAt this# k#))
                       `(put [this# k# v#] (throw (UnsupportedOperationException.)))
                       `(remove [this# k#] (throw (UnsupportedOperationException.)))
                       `(putAll [this# m#] (throw (UnsupportedOperationException.)))
                       `(clear [this#] (throw (UnsupportedOperationException.)))
                       `(keySet [this#] (set (keys this#)))
                       `(values [this#] (vals this#))
                       `(entrySet [this#] (set this#)))])
      ]
     (let [[i m] (-> [interfaces methods] irecord eqhash iobj ilookup imap ijavamap)]
       `(deftype* ~tagname ~classname ~(conj hinted-fields '__meta '__extmap) 
          :implements ~(vec i) 
          ~@m))))))

(defn- build-positional-factory
  "Used to build a positional factory for a given type/record.  Because of the
  limitation of 20 arguments to Clojure functions, this factory needs to be
  constructed to deal with more arguments.  It does this by building a straight
  forward type/record ctor call in the <=20 case, and a call to the same
  ctor pulling the extra args out of the & overage parameter.  Finally, the
  arity is constrained to the number of expected fields and an ArityException
  will be thrown at runtime if the actual arg count does not match."
  [nom classname fields]
  (let [fn-name (symbol (str '-> nom))
        [field-args over] (split-at 20 fields)
        field-count (count fields)
        arg-count (count field-args)
        over-count (count over)
        docstring (str "Positional factory function for class " classname ".")]
    `(defn ~fn-name
       ~docstring
       [~@field-args ~@(if (seq over) '[& overage] [])]
       ~(if (seq over)
          `(if (= (count ~'overage) ~over-count)
             (new ~classname
                  ~@field-args
                  ~@(for [i (range 0 (count over))]
                      (list `nth 'overage i)))
             (throw (clojure.lang.ArityException. (+ ~arg-count (count ~'overage)) (name '~fn-name))))
          `(new ~classname ~@field-args)))))

(defn- validate-fields
  ""
  [fields]
  (when-not (vector? fields)
    (throw (AssertionError. "No fields vector given.")))
  (let [specials #{'__meta '__extmap}]
    (when (some specials fields)
      (throw (AssertionError. (str "The names in " specials " cannot be used as field names for types or records."))))))

(defmacro defrecord
  "将来、仕様変更の可能性あり
  
  (defrecord name [fields*]  options* specs*)
  
  現在、提供されているオプションはありません。

  各specは、プロトコル名もしくはインタフェース名と、それに続く0個以上の
  メソッド定義からなります：

  protocol-or-interface-or-Object
  (methodName [args*] body)*

  与えられた名前をもつクラスを動的に生成します。生成されたクラスは現在の
  名前空間と同名のパッケージに属し、与えられたフィールドとメソッドをもちます。

  生成されたクラスはfieldsによって名前のつけられた(イミュータブルな)
  フィールドをもちます。fieldsには各フィールドの型を指定するために型ヒントを
  付加できます。実装するプロトコルやインタフェースとメソッドを任意で記述する
  ことができます。メソッドはプロトコルやインタフェースで宣言されたもので
  なければなりません。また、メソッドの内部はクロージャではないため、ローカル
  環境にはレコードのフィールド以外は含まれないことに注意して下さい。ただし、
  レコードのフィールドについては、メソッド内からでも直接アクセスすることが
  できます。

  メソッド定義は以下のフォームをとります：

  (methodname [args*] body)

  引数と戻り値の型は、それぞれ引数とメソッド名のシンボルに型ヒントを付加
  することで指定できます。型の指定がない場合には型が自動的に推論されるため、
  型ヒントは曖昧さを解決する必要がある場合にのみ使用するべきです。

  プロトコルやインタフェースがもつすべてのメソッドに対して、実装を与える
  ようにするべきです。Objectで定義されているメソッドをオーバーライドする
  こともできます。第1引数として、ターゲットオブジェクト(Javaでいう'this')を
  受ける引数がなければならないことに注意して下さい。したがって、インタフェースに
  対するメソッドは、インタフェースの宣言にあるよりも1つ多く引数をとることに
  なります。メソッドの先頭へ戻るrecurの呼び出しにはターゲットオブジェクトを
  渡すべきではないことにも注意して下さい。ターゲットオブジェクトは自動的に
  受け渡され、他のオブジェクトと置き換えることはできません。

  メソッド内では、(名前空間修飾されていない)レコード名をクラスの名前として
  (newやinstance?等を使う場合に)使用することができます。

  生成されたクラスは、自動的に(clojure.langに属する)いくつかのインタフェースを
  実装します。実装されるインタフェースは、IObj(メタデータを付加できるように
  するため)、IPersistentMap、およびこれらのすべてのスーパーインタフェースです。

  さらに、defrecordは型と値をベースとした=を定義します。java.util.Mapの
  不変条件を満たすように、.hashCodeと.equalsの定義もします。

  AOTコンパイルをする場合、現在の名前空間と同名のパッケージに属する、
  与えられた名前をもつクラスのバイトコードへコンパイルされ、*compile-path*で
  指定されたディレクトリに.classファイルとして書き出されます。

  defrecordにより2つのコンストラクタが定義されます。ひとつは、各フィールドの
  値と2つのマップを引数にとるものです。1つめのマップはレコードに付加される
  メタデータで(なければnil)、2つめのマップはレコードを拡張するフィールド
  (なければnil)です。もうひとつのコンストラクタはフィールドの値のみを引数に
  とるものです。現在、__metaおよび__extmapという名前のフィールドは予約されて
  おり、自身でレコードを定義する場合には使用するべきではありません。

  (defrecord TypeName)とした場合、次の2つのファクトリー関数が定義されます。
  ->TypeNameは、フィールドの値をレコードの定義に現れる順で引数にとります。
  map->TypeNameは、キーワード化したフィールド名と、対応するフィールドの値と
  からなるマップを引数にとります。"
  {:added "1.2"
   :arglists '([name [& fields] & opts+specs])}

  [name fields & opts+specs]
  (validate-fields fields)
  (let [gname name
        [interfaces methods opts] (parse-opts+specs opts+specs)
        ns-part (namespace-munge *ns*)
        classname (symbol (str ns-part "." gname))
        hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))]
    `(let []
       (declare ~(symbol (str  '-> gname)))
       (declare ~(symbol (str 'map-> gname)))
       ~(emit-defrecord name gname (vec hinted-fields) (vec interfaces) methods)
       (import ~classname)
       ~(build-positional-factory gname classname fields)
       (defn ~(symbol (str 'map-> gname))
         ~(str "Factory function for class " classname ", taking a map of keywords to field values.")
         ([m#] (~(symbol (str classname "/create")) m#)))
       ~classname)))

(defn- emit-deftype* 
  "Do not use this directly - use deftype"
  [tagname name fields interfaces methods]
  (let [classname (with-meta (symbol (str (namespace-munge *ns*) "." name)) (meta name))
        interfaces (conj interfaces 'clojure.lang.IType)]
    `(deftype* ~tagname ~classname ~fields 
       :implements ~interfaces 
       ~@methods)))

(defmacro deftype
  "将来、仕様変更の可能性あり
  
  (deftype name [fields*]  options* specs*)
  
  現在、提供されているオプションはありません。

  各specは、プロトコル名もしくはインタフェース名と、それに続く0個以上の
  メソッド定義からなります：

  protocol-or-interface-or-Object
  (methodName [args*] body)*

  与えられた名前をもつクラスを動的に生成します。生成されたクラスは現在の
  名前空間と同名のパッケージに属し、与えられたフィールドとメソッドをもちます。

  生成されたクラスはfieldsによって名前のつけられた(デフォルトではイミュータブルな)
  フィールドをもちます。fieldsには各フィールドの型を指定するために型ヒントを
  付加できます。実装するプロトコルやインタフェースとメソッドを任意で記述する
  ことができます。メソッドはプロトコルやインタフェースで宣言されたもので
  なければなりません。また、メソッドの内部はクロージャではないため、ローカル
  環境にはこのデータ型のフィールド以外は含まれないことに注意して下さい。
  ただし、フィールドについては、メソッド内からでも直接アクセスすることができます。
  フィールドには、:volatile-mutable trueあるいは:unsynchronized-mutable true
  というメタデータを付加できます。これらのメタデータが付加された場合、メソッド内で
  そのフィールドに対して(set! afield aval)が可能になります。ミュータブルな
  フィールドは正しく使うことが極めて難しく、たとえばClojureの参照型のような
  高度な構造の構築を容易にするためにのみ提供されています。これらについては、
  十分な理解がある場合にのみ使用して下さい。:volatile-mutableや
  :unsynchronized-mutableのセマンティクスや付随する効果について
  理解していない場合は、これらを使用するべきではありません。

  メソッド定義は以下のフォームをとります：

  (methodname [args*] body)

  引数と戻り値の型は、それぞれ引数とメソッド名のシンボルに型ヒントを付加
  することで指定できます。型の指定がない場合には型が自動的に推論されるため、
  型ヒントは曖昧さを解決する必要がある場合にのみ使用するべきです。

  プロトコルやインタフェースがもつすべてのメソッドに対して、実装を与える
  ようにするべきです。Objectで定義されているメソッドをオーバーライドする
  こともできます。第1引数として、ターゲットオブジェクト(Javaでいう'this')を
  受ける引数がなければならないことに注意して下さい。したがって、インタフェースに
  対するメソッドは、インタフェースの宣言にあるよりも1つ多く引数をとることに
  なります。メソッドの先頭へ戻るrecurの呼び出しにはターゲットオブジェクトを
  渡すべきではないことにも注意して下さい。ターゲットオブジェクトは自動的に
  受け渡され、他のオブジェクトと置き換えることはできません。

  メソッド内では、(名前空間修飾されていない)レコード名をクラスの名前として
  (newやinstance?等を使う場合に)使用することができます。

  AOTコンパイルをする場合、現在の名前空間と同名のパッケージに属する、
  与えられた名前をもつクラスのバイトコードへコンパイルされ、*compile-path*で
  指定されたディレクトリに.classファイルとして書き出されます。

  defrecordは、フィールドの値を引数にとるコンストラクタを定義します。現在、
  __metaおよび__extmapという名前のフィールドは予約されており、自身で
  データ型を定義する場合には使用するべきではありません。

  (deftype TypeName ...)とした場合、->TypeNameという名前のファクトリー関数が
  定義されます。->TypeNameは、フィールドの値をデータ型の定義に現れる順で
  引数にとります。"
  {:added "1.2"
   :arglists '([name [& fields] & opts+specs])}

  [name fields & opts+specs]
  (validate-fields fields)
  (let [gname name
        [interfaces methods opts] (parse-opts+specs opts+specs)
        ns-part (namespace-munge *ns*)
        classname (symbol (str ns-part "." gname))
        hinted-fields fields
        fields (vec (map #(with-meta % nil) fields))
        [field-args over] (split-at 20 fields)]
    `(let []
       ~(emit-deftype* name gname (vec hinted-fields) (vec interfaces) methods)
       (import ~classname)
       ~(build-positional-factory gname classname fields)
       ~classname)))

;;;;;;;;;;;;;;;;;;;;;;; protocols ;;;;;;;;;;;;;;;;;;;;;;;;

(defn- expand-method-impl-cache [^clojure.lang.MethodImplCache cache c f]
  (if (.map cache)
    (let [cs (assoc (.map cache) c (clojure.lang.MethodImplCache$Entry. c f))]
      (clojure.lang.MethodImplCache. (.protocol cache) (.methodk cache) cs))
    (let [cs (into1 {} (remove (fn [[c e]] (nil? e)) (map vec (partition 2 (.table cache)))))
          cs (assoc cs c (clojure.lang.MethodImplCache$Entry. c f))]
      (if-let [[shift mask] (maybe-min-hash (map hash (keys cs)))]
        (let [table (make-array Object (* 2 (inc mask)))
              table (reduce1 (fn [^objects t [c e]]
                               (let [i (* 2 (int (shift-mask shift mask (hash c))))]
                                 (aset t i c)
                                 (aset t (inc i) e)
                                 t))
                             table cs)]
          (clojure.lang.MethodImplCache. (.protocol cache) (.methodk cache) shift mask table))
        (clojure.lang.MethodImplCache. (.protocol cache) (.methodk cache) cs)))))

(defn- super-chain [^Class c]
  (when c
    (cons c (super-chain (.getSuperclass c)))))

(defn- pref
  ([] nil)
  ([a] a) 
  ([^Class a ^Class b]
     (if (.isAssignableFrom a b) b a)))

(defn find-protocol-impl [protocol x]
  (if (instance? (:on-interface protocol) x)
    x
    (let [c (class x)
          impl #(get (:impls protocol) %)]
      (or (impl c)
          (and c (or (first (remove nil? (map impl (butlast (super-chain c)))))
                     (when-let [t (reduce1 pref (filter impl (disj (supers c) Object)))]
                       (impl t))
                     (impl Object)))))))

(defn find-protocol-method [protocol methodk x]
  (get (find-protocol-impl protocol x) methodk))

(defn- protocol?
  [maybe-p]
  (boolean (:on-interface maybe-p)))

(defn- implements? [protocol atype]
  (and atype (.isAssignableFrom ^Class (:on-interface protocol) atype)))

(defn extends? 
  "引数として与えたatypeがプロトコルをextendしている場合にtrueを返します。"
  {:added "1.2"}
  [protocol atype]
  (boolean (or (implements? protocol atype) 
               (get (:impls protocol) atype))))

(defn extenders 
  "プロトコルを明示的にextendしている型のコレクションを返します。"
  {:added "1.2"}
  [protocol]
  (keys (:impls protocol)))

(defn satisfies? 
  "引数として与えたxがプロトコルを満たしている場合にtrueを返します。"
  {:added "1.2"}
  [protocol x]
  (boolean (find-protocol-impl protocol x)))

(defn -cache-protocol-fn [^clojure.lang.AFunction pf x ^Class c ^clojure.lang.IFn interf]
  (let [cache  (.__methodImplCache pf)
        f (if (.isInstance c x)
            interf 
            (find-protocol-method (.protocol cache) (.methodk cache) x))]
    (when-not f
      (throw (IllegalArgumentException. (str "No implementation of method: " (.methodk cache) 
                                             " of protocol: " (:var (.protocol cache)) 
                                             " found for class: " (if (nil? x) "nil" (.getName (class x)))))))
    (set! (.__methodImplCache pf) (expand-method-impl-cache cache (class x) f))
    f))

(defn- emit-method-builder [on-interface method on-method arglists]
  (let [methodk (keyword method)
        gthis (with-meta (gensym) {:tag 'clojure.lang.AFunction})
        ginterf (gensym)]
    `(fn [cache#]
       (let [~ginterf
             (fn
               ~@(map 
                  (fn [args]
                    (let [gargs (map #(gensym (str "gf__" % "__")) args)
                          target (first gargs)]
                      `([~@gargs]
                          (. ~(with-meta target {:tag on-interface}) (~(or on-method method) ~@(rest gargs))))))
                  arglists))
             ^clojure.lang.AFunction f#
             (fn ~gthis
               ~@(map 
                  (fn [args]
                    (let [gargs (map #(gensym (str "gf__" % "__")) args)
                          target (first gargs)]
                      `([~@gargs]
                          (let [cache# (.__methodImplCache ~gthis)
                                f# (.fnFor cache# (clojure.lang.Util/classOf ~target))]
                            (if f# 
                              (f# ~@gargs)
                              ((-cache-protocol-fn ~gthis ~target ~on-interface ~ginterf) ~@gargs))))))
                  arglists))]
         (set! (.__methodImplCache f#) cache#)
         f#))))

(defn -reset-methods [protocol]
  (doseq [[^clojure.lang.Var v build] (:method-builders protocol)]
    (let [cache (clojure.lang.MethodImplCache. protocol (keyword (.sym v)))]
      (.bindRoot v (build cache)))))

(defn- assert-same-protocol [protocol-var method-syms]
  (doseq [m method-syms]
    (let [v (resolve m)
          p (:protocol (meta v))]
      (when (and v (bound? v) (not= protocol-var p))
        (binding [*out* *err*]
          (println "Warning: protocol" protocol-var "is overwriting"
                   (if p
                     (str "method " (.sym v) " of protocol " (.sym p))
                     (str "function " (.sym v)))))))))

(defn- emit-protocol [name opts+sigs]
  (let [iname (symbol (str (munge (namespace-munge *ns*)) "." (munge name)))
        [opts sigs]
        (loop [opts {:on (list 'quote iname) :on-interface iname} sigs opts+sigs]
          (condp #(%1 %2) (first sigs) 
            string? (recur (assoc opts :doc (first sigs)) (next sigs))
            keyword? (recur (assoc opts (first sigs) (second sigs)) (nnext sigs))
            [opts sigs]))
        sigs (when sigs
               (reduce1 (fn [m s]
                          (let [name-meta (meta (first s))
                                mname (with-meta (first s) nil)
                                [arglists doc]
                                (loop [as [] rs (rest s)]
                                  (if (vector? (first rs))
                                    (recur (conj as (first rs)) (next rs))
                                    [(seq as) (first rs)]))]
                            (when (some #{0} (map count arglists))
                              (throw (IllegalArgumentException. (str "Definition of function " mname " in protocol " name " must take at least one arg."))))
                            (when (m (keyword mname))
                              (throw (IllegalArgumentException. (str "Function " mname " in protocol " name " was redefined. Specify all arities in single definition."))))
                            (assoc m (keyword mname)
                                   (merge name-meta
                                          {:name (vary-meta mname assoc :doc doc :arglists arglists)
                                           :arglists arglists
                                           :doc doc}))))
                        {} sigs))
        meths (mapcat (fn [sig]
                        (let [m (munge (:name sig))]
                          (map #(vector m (vec (repeat (dec (count %))'Object)) 'Object) 
                               (:arglists sig))))
                      (vals sigs))]
  `(do
     (defonce ~name {})
     (gen-interface :name ~iname :methods ~meths)
     (alter-meta! (var ~name) assoc :doc ~(:doc opts))
     ~(when sigs
        `(#'assert-same-protocol (var ~name) '~(map :name (vals sigs))))
     (alter-var-root (var ~name) merge 
                     (assoc ~opts 
                       :sigs '~sigs 
                       :var (var ~name)
                       :method-map 
                         ~(and (:on opts)
                               (apply hash-map 
                                      (mapcat 
                                       (fn [s] 
                                         [(keyword (:name s)) (keyword (or (:on s) (:name s)))])
                                       (vals sigs))))
                       :method-builders 
                        ~(apply hash-map 
                                (mapcat 
                                 (fn [s]
                                   [`(intern *ns* (with-meta '~(:name s) (merge '~s {:protocol (var ~name)})))
                                    (emit-method-builder (:on-interface opts) (:name s) (:on s) (:arglists s))])
                                 (vals sigs)))))
     (-reset-methods ~name)
     '~name)))

(defmacro defprotocol 
  "プロトコルは、メソッド名とそのシグネチャの集まりに名前をつけたものです：
  (defprotocol AProtocolName

    ;省略可能なドキュメント文字列
    \"A doc string for AProtocol abstraction\"

    ;メソッドシグネチャ
    (bar [this a b] \"bar docs\")
    (baz [this a] [this a b] [this a b c] \"baz docs\"))

  プロトコルは実装を提供しません。プロトコル自体と各メソッドにドキュメント
  文字列を付加できます。上のような記述により、多相的な関数のセットと
  プロトコルオブジェクトが生成されます。プロトコルおよびすべての関数は、
  定義された名前空間により名前空間修飾されます。生成される関数は
  第1引数の型によってディスパッチします。そのため、第1引数は暗黙の
  ターゲットオブジェクト(Javaでいう'this')である必要があります。
  defprotocolは実行時に処理されるため、コンパイル時には何もしません。
  また、新たに型やクラスを定義することもありません。(FIXME:　要事実確認)
  プロトコルのメソッドの実装はextendを使って与えることができます。

  defprotocolはプロトコルと同名の対応するインタフェースを自動的に
  生成します。たとえば、my.ns/Protocolというプロトコルであれば、
  my.ns.Protocolというインタフェースが生成されます。生成される
  インタフェースはプロトコルの関数に対応するメソッドをもつため、
  インタフェースを実装したインスタンスに対しては自動的にプロトコルの
  関数が適用可能になります。

  reifyやdeftypeはプロトコルを直接サポートしているため、これらでは
  defprotocolで生成されるインタフェースを使用するべきではないことに
  注意して下さい：

  (defprotocol P 
    (foo [this]) 
    (bar-me [this] [this y]))

  (deftype Foo [a b c] 
   P
    (foo [this] a)
    (bar-me [this] b)
    (bar-me [this y] (+ c y)))
  
  (bar-me (Foo. 1 2 3) 42)
  => 45

  (foo 
    (let [x 42]
      (reify P 
        (foo [this] 17)
        (bar-me [this] x)
        (bar-me [this y] x))))
  => 17"
  {:added "1.2"} 
  [name & opts+sigs]
  (emit-protocol name opts+sigs))

(defn extend 
  "プロトコルのメソッドの実装はextendを使って与えることができます：

  (extend AType
    AProtocol
     {:foo an-existing-fn
      :bar (fn [a b] ...)
      :baz (fn ([a]...) ([a b] ...)...)}
    BProtocol 
      {...} 
    ...)

  extendは、型あるいはクラス(あるいはインタフェース、以下参照)と、
  一対以上のプロトコルとメソッドマップを引数にとります。extendは、
  ATypeに対してメソッドが呼ばれたときに、与えられたメソッドが
  呼び出されるようにプロトコルのメソッドの多相性を拡張します。

  メソッドマップは、キーワード化したメソッド名と通常の関数とからなる
  マップです。これにより、既存の関数や関数のマップを容易に再利用する
  ことができ、継承や集約を使用せずにコードの再利用やミックスインを
  実現できます。インタフェースをextendすることも可能です。これは
  主にホスト(たとえば、Java)との相互運用を容易にするためのものです。
  しかし、1つのクラスが複数のインタフェースを実装することが可能な
  ため、あるプロトコルを複数のインタフェースでextendする場合、
  偶発的に多重継承が発生することがあります。このような場合に、
  使用する実装を指定する方法については現在検討中です。
  プロトコルをnilに対してextendすることもできます。

  メソッド定義を明示的に与える場合(つまり、既存の関数や
  メソッドマップを再利用しない場合)、extend-typeマクロや
  extend-protocolマクロを使用する方が便利かもしれません。

  同じ型に対して複数回extendを呼べることに注意して下さい。
  1つのextendフォームの中ですべてのプロトコルに対する定義を
  与える必要はありません。

  以下も参照のこと：
  extends?, satisfies?, extenders"
  {:added "1.2"} 
  [atype & proto+mmaps]
  (doseq [[proto mmap] (partition 2 proto+mmaps)]
    (when-not (protocol? proto)
      (throw (IllegalArgumentException.
              (str proto " is not a protocol"))))
    (when (implements? proto atype)
      (throw (IllegalArgumentException. 
              (str atype " already directly implements " (:on-interface proto) " for protocol:"  
                   (:var proto)))))
    (-reset-methods (alter-var-root (:var proto) assoc-in [:impls atype] mmap))))

(defn- emit-impl [[p fs]]
  [p (zipmap (map #(-> % first keyword) fs)
             (map #(cons 'fn (drop 1 %)) fs))])

(defn- emit-hinted-impl [c [p fs]]
  (let [hint (fn [specs]
               (let [specs (if (vector? (first specs)) 
                                        (list specs) 
                                        specs)]
                 (map (fn [[[target & args] & body]]
                        (cons (apply vector (vary-meta target assoc :tag c) args)
                              body))
                      specs)))]
    [p (zipmap (map #(-> % first name keyword) fs)
               (map #(cons 'fn (hint (drop 1 %))) fs))]))

(defn- emit-extend-type [c specs]
  (let [impls (parse-impls specs)]
    `(extend ~c
             ~@(mapcat (partial emit-hinted-impl c) impls))))

(defmacro extend-type 
  "extendの呼び出しに展開されるマクロです。defrecordやdeftypeと同様に、
  メソッドを並べて定義することができます。extendで必要となるマップは、extend-typeが
  自動的に生成します。すべての関数の第1引数に、extendするクラスが型ヒントとして
  付加されます。

  (extend-type MyType 
    Countable
      (cnt [c] ...)
    Foo
      (bar [x y] ...)
      (baz ([x] ...) ([x y & zs] ...)))

  これは以下のように展開されます：

  (extend MyType
   Countable
     {:cnt (fn [c] ...)}
   Foo
     {:baz (fn ([x] ...) ([x y & zs] ...))
      :bar (fn [x y] ...)})"
  {:added "1.2"} 
  [t & specs]
  (emit-extend-type t specs))

(defn- emit-extend-protocol [p specs]
  (let [impls (parse-impls specs)]
    `(do
       ~@(map (fn [[t fs]]
                `(extend-type ~t ~p ~@fs))
              impls))))

(defmacro extend-protocol 
  "複数の型に対して同じプロトコルの実装を一度に与える場合に使います。
  1つのプロトコルとそのプロトコルの1つ以上の実装を入力としてとります。
  extend-typeの呼び出しに展開されます：

  (extend-protocol Protocol
    AType
      (foo [x] ...)
      (bar [x y] ...)
    BType
      (foo [x] ...)
      (bar [x y] ...)
    AClass
      (foo [x] ...)
      (bar [x y] ...)
    nil
      (foo [x] ...)
      (bar [x y] ...))

  これは以下のように展開されます：

  (do
   (clojure.core/extend-type AType Protocol 
     (foo [x] ...) 
     (bar [x y] ...))
   (clojure.core/extend-type BType Protocol 
     (foo [x] ...) 
     (bar [x y] ...))
   (clojure.core/extend-type AClass Protocol 
     (foo [x] ...) 
     (bar [x y] ...))
   (clojure.core/extend-type nil Protocol 
     (foo [x] ...) 
     (bar [x y] ...)))"
  {:added "1.2"}

  [p & specs]
  (emit-extend-protocol p specs))

