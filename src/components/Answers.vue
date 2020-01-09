<template>
    <div>

                        
<h4>Составление слов из букв и других слов</h4>

<div v-if="error" class="alert alert-danger alert-dismissible fade show" role="alert">
  Произошла ошибка при загрузке данных
  <button type="button" class="close" data-dismiss="alert" aria-label="Close">
    <span aria-hidden="true">&times;</span>
  </button>
</div>

<div class="alert alert-secondary well" role="alert">
    <p>
        <span>Введите слово или последовательность букв, из которых нужно составить слова</span>
    </p>
    <form class="form-inline" method="get" action="/answers">
        <div class="form-group">
            <input type="text" class="form-control form-control-lg margin-right" placeholder="" name="word" v-model="word" maxlength="30" autocomplete="off">
        </div>
        <button class="btn btn-primary btn-lg" v-on:click.stop.prevent="onSubmit">Искать варианты</button>
    </form>
</div>

    <Waiting ref="waitingModal"></Waiting>     

    </div>
</template>

<script>

import axios from 'axios';
import Waiting from './modal/Waiting.vue';

export default {
    components: {
        Waiting
    },
    data: function() {
        return {
            word: '',
            words: [],
            error: false
        }
    },
    methods: {
        onSubmit: function () {  
            this.$modal.show('waiting-modal');
    
            axios.get('http://combination-words.local/words/' + encodeURI(this.word))
            .then(response => {
                this.words = response.data.data;
                this.error = false;   
                document.title = 'Слова, которые можно составить из "' + this.word + '"';
            })
            .catch(e => {
                this.error = e;
            }).then(() => {
                this.$modal.hide('waiting-modal');
            });
        }
    }     
}
</script>