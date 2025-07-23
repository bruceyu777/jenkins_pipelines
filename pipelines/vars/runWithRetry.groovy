def call(int attempts, List<Integer> sleepsSec = [], String cmd) {
    for (int i = 1; i <= attempts; i++) {
        int rc = sh(script: cmd, returnStatus: true)
        if (rc == 0) return
        echo "SVN failed (rc=${rc}) attempt ${i}/${attempts}"
        if (i < attempts) {
            int wait = (sleepsSec.size() >= i) ? sleepsSec[i-1] : 10
            echo "Sleeping ${wait}s before retry..."
            sleep time: wait, unit: 'SECONDS'
        }
    }
    error "SVN command failed after ${attempts} attempts."
}
